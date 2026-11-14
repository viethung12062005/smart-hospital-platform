"""Appointment event consumer and no-show scoring (ML-06, ML-07; 04 §2.4).

Each event is processed in ONE transaction together with the inbox mark: update `appointment_facts`, `patient_stats`,
write `predictions` and the `shp.ml.no-show-scored.v1` outbox event. Events with a `version` ≤ the stored version
are ignored (redelivery, out of order). No production model yet: appointments are still recorded, just not scored
(AC-15.1: an ML failure must not affect reminders). No PII: only IDs, 10-year age band and timestamps.
No PII: only IDs, 10-year age band and timestamps.
"""

from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any

from pymongo import MongoClient
from pymongo.client_session import ClientSession
from pymongo.database import Database

from shp_ml.features import LATE_CANCEL, PatientStats, features_for
from shp_ml.metrics import EVENTS, HIGH_RISK, SCORING_SKIPPED
from shp_ml.model import MODEL_NAME
from shp_ml.serving import ModelHolder, Scored
from shp_platform import events, mongo

BOOKED = "shp.scheduling.appointment-booked.v1"
CANCELLED = "shp.scheduling.appointment-cancelled.v1"
RESCHEDULED = "shp.scheduling.appointment-rescheduled.v1"
STATUS = "shp.scheduling.appointment-status-changed.v1"
MERGED = "shp.clinical.patient-merged.v1"
TYPES = [BOOKED, CANCELLED, RESCHEDULED, STATUS, MERGED]
SCORED = "shp.ml.no-show-scored.v1"

Doc = dict[str, Any]


def ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def iso(value: datetime) -> str:
    return _aware(value).astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=UTC)  # pymongo returns naive datetimes (UTC)


class NoShowConsumer:
    def __init__(self, client: MongoClient[Doc], db: Database[Doc], holder: ModelHolder, consumer: str = "ml") -> None:
        self.client = client
        self.db = db
        self.holder = holder
        self.consumer = consumer
        self._handlers: dict[str, Callable[[ClientSession, dict[str, Any]], None]] = {
            BOOKED: self._booked,
            CANCELLED: self._cancelled,
            RESCHEDULED: self._rescheduled,
            STATUS: self._status,
            MERGED: self._merged,
        }

    def handle(self, event: dict[str, Any]) -> None:
        """Called from shp_platform.events.consume (schema already validated). Error → transaction rollback → retry or
        DLQ."""
        handler = self._handlers.get(event["type"])
        if handler is None:
            raise events.PermanentError(f"unregistered type: {event['type']}")
        processed = mongo.handle_once(self.client, self.db, self.consumer, event, lambda s: handler(s, event))
        EVENTS.labels(type=event["type"].split(".")[2], outcome="processed" if processed else "duplicate").inc()

    # ---------------- E1 ----------------
    def _booked(self, s: ClientSession, evt: dict[str, Any]) -> None:
        d = evt["data"]
        fact = {
            "_id": d["appointmentId"],
            "patientId": d["patientId"],
            "practitionerId": d["practitionerId"],
            "specialty": d["specialty"],
            "channel": d["channel"],
            "ageBand": d.get("ageBand") or "unknown",
            "bookedAt": ts(d["bookedAt"]),
            "startAt": ts(d["start"]),
            "outcome": "pending",
            "version": int(d["version"]),
        }
        if not self._newer(s, fact["_id"], fact["version"]):
            return
        self.db.appointment_facts.replace_one({"_id": fact["_id"]}, fact, upsert=True, session=s)
        self._score(s, fact, evt.get("traceparent"))

    # ---------------- E3 ----------------
    def _rescheduled(self, s: ClientSession, evt: dict[str, Any]) -> None:
        d = evt["data"]
        old = self.db.appointment_facts.find_one({"_id": d["oldAppointmentId"]}, session=s)
        if old is not None and old["outcome"] == "pending":
            # rescheduling is not a late cancellation: do not increment lateCancelTotal
            self.db.appointment_facts.update_one(
                {"_id": old["_id"]}, {"$set": {"outcome": "cancelled", "outcomeAt": ts(evt["time"])}}, session=s
            )
        fact = {
            "_id": d["newAppointmentId"],
            "patientId": d["patientId"],
            "practitionerId": d["practitionerId"],
            "specialty": d["specialty"],
            "channel": old["channel"] if old else "web",
            "ageBand": old["ageBand"] if old else "unknown",
            "bookedAt": ts(evt["time"]),
            "startAt": ts(d["newStart"]),
            "outcome": "pending",
            "version": int(d["version"]),
        }
        if self._newer(s, fact["_id"], fact["version"]):
            self.db.appointment_facts.replace_one({"_id": fact["_id"]}, fact, upsert=True, session=s)
            self._score(s, fact, evt.get("traceparent"))

    # ---------------- E2 ----------------
    def _cancelled(self, s: ClientSession, evt: dict[str, Any]) -> None:
        d = evt["data"]
        fact = self.db.appointment_facts.find_one({"_id": d["appointmentId"]}, session=s)
        if fact is not None and (fact["outcome"] != "pending" or int(d["version"]) <= fact.get("version", 0)):
            return
        if fact is not None:
            self.db.appointment_facts.update_one(
                {"_id": fact["_id"]},
                {"$set": {"outcome": "cancelled", "outcomeAt": ts(d["cancelledAt"]), "version": int(d["version"])}},
                session=s,
            )
        if float(d["hoursBeforeStart"]) < LATE_CANCEL.total_seconds() / 3600:
            self._inc(s, d["patientId"], {"lateCancelTotal": 1})

    # ---------------- E5 ----------------
    def _status(self, s: ClientSession, evt: dict[str, Any]) -> None:
        d = evt["data"]
        new = d["newStatus"]
        if new not in ("fulfilled", "noshow"):
            return
        fact = self.db.appointment_facts.find_one({"_id": d["appointmentId"]}, session=s)
        if fact is not None:
            if fact["outcome"] != "pending" or int(d["version"]) <= fact.get("version", 0):
                return
            self.db.appointment_facts.update_one(
                {"_id": fact["_id"]},
                {"$set": {"outcome": new, "outcomeAt": ts(d["changedAt"]), "version": int(d["version"])}},
                session=s,
            )
        at = _aware(fact["startAt"]) if fact else ts(d["changedAt"])
        if new == "noshow":
            self._inc(s, d["patientId"], {"bookedTotal": 1, "noshowTotal": 1}, {"lastNoshowAt": at})
        else:
            self._inc(s, d["patientId"], {"bookedTotal": 1, "fulfilledTotal": 1}, {"lastVisitAt": at})

    # ---------------- E7 ----------------
    def _merged(self, s: ClientSession, evt: dict[str, Any]) -> None:
        d = evt["data"]
        src, dst = d["sourcePatientId"], d["targetPatientId"]
        stats = self.db.patient_stats.find_one({"_id": src}, session=s)
        if stats is not None:
            inc = {k: int(stats.get(k, 0)) for k in ("bookedTotal", "fulfilledTotal", "noshowTotal", "lateCancelTotal")}
            mx = {k: _aware(stats[k]) for k in ("lastNoshowAt", "lastVisitAt") if stats.get(k)}
            self._inc(s, dst, inc, mx)
            self.db.patient_stats.delete_one({"_id": src}, session=s)
        self.db.appointment_facts.update_many({"patientId": src}, {"$set": {"patientId": dst}}, session=s)
        self.db.predictions.update_many({"patientId": src}, {"$set": {"patientId": dst}}, session=s)

    # ---------------- helpers ----------------
    def _newer(self, s: ClientSession, appointment_id: str, version: int) -> bool:
        cur = self.db.appointment_facts.find_one({"_id": appointment_id}, {"version": 1}, session=s)
        return cur is None or version > int(cur.get("version", 0))

    def _inc(
        self, s: ClientSession, patient_id: str, inc: dict[str, int], mx: dict[str, datetime] | None = None
    ) -> None:
        update: dict[str, Any] = {"$inc": inc, "$set": {"updatedAt": datetime.now(UTC)}}
        if mx:
            update["$max"] = mx
        self.db.patient_stats.update_one({"_id": patient_id}, update, upsert=True, session=s)

    def stats_for(self, patient_id: str, s: ClientSession | None = None) -> PatientStats:
        doc = self.db.patient_stats.find_one({"_id": patient_id}, session=s) or {}
        last = doc.get("lastNoshowAt")
        return PatientStats(
            booked_total=int(doc.get("bookedTotal", 0)),
            noshow_total=int(doc.get("noshowTotal", 0)),
            late_cancel_total=int(doc.get("lateCancelTotal", 0)),
            last_noshow_at=_aware(last) if last else None,
        )

    def _score(self, s: ClientSession, fact: dict[str, Any], traceparent: str | None) -> None:
        feats = features_for(
            fact["bookedAt"],
            fact["startAt"],
            fact["specialty"],
            fact["channel"],
            fact["ageBand"],
            self.stats_for(fact["patientId"], s),
        )
        result = self.holder.score(feats)
        if result is None:
            SCORING_SKIPPED.labels(reason="no-production-model").inc()
            return
        now = datetime.now(UTC)
        self._save(s, fact, feats, result, now, shadow=False)
        HIGH_RISK.labels(model_version=result.model_version, high_risk=str(result.high_risk).lower()).inc()
        mongo.add_to_outbox(
            self.db,
            s,
            events.create(
                "ml",
                SCORED,
                f"Appointment/{fact['_id']}",
                {
                    "appointmentId": fact["_id"],
                    "patientId": fact["patientId"],
                    "start": iso(fact["startAt"]),
                    "score": result.score,
                    "threshold": result.threshold,
                    "highRisk": result.high_risk,
                    "modelName": MODEL_NAME,
                    "modelVersion": result.model_version,
                    "scoredAt": iso(now),
                },
                traceparent,
            ),
        )
        shadow = self.holder.shadow(feats, result)
        if shadow is not None:
            self._save(s, fact, feats, shadow, now, shadow=True)

    def _save(
        self, s: ClientSession, fact: dict[str, Any], feats: dict[str, Any], r: Scored, now: datetime, shadow: bool
    ) -> None:
        self.db.predictions.replace_one(
            {"appointmentId": fact["_id"], "shadow": shadow, "modelVersion": r.model_version},
            {
                "appointmentId": fact["_id"],
                "patientId": fact["patientId"],
                "modelName": MODEL_NAME,
                "modelVersion": r.model_version,
                "score": r.score,
                "threshold": r.threshold,
                "highRisk": r.high_risk,
                "features": feats,  # AC-15.2: store the features that were used
                "predictedAt": now,
                "latencyMs": r.latency_ms,
                "shadow": shadow,
                "startAt": fact["startAt"],
            },
            upsert=True,
            session=s,
        )


def labeled_since(db: Database[Doc], since: datetime | None) -> int:
    q: dict[str, Any] = {"outcome": {"$in": ["fulfilled", "noshow"]}}
    if since is not None:
        q["outcomeAt"] = {"$gte": since}
    return db.appointment_facts.count_documents(q)


def recent_window(days: int) -> datetime:
    return datetime.now(UTC) - timedelta(days=days)
