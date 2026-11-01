"""Khởi tạo MongoDB `ml` khi service khởi động (ML-11; 04 §2.2). Idempotent, chạy lại không lỗi.

Cùng quy tắc với contracts/db/mongo-notification-ml.js (validator ageBand/channel/outcome). `predictions` là time-series
nếu script hạ tầng đã tạo như vậy; nếu service tự tạo thì là collection thường có unique (appointmentId, shadow).
"""

from typing import Any

from pymongo import ASCENDING, DESCENDING
from pymongo.database import Database
from pymongo.errors import CollectionInvalid

from shp_platform.mongo import ensure_messaging_indexes

PREDICTION_TTL_SECONDS = 400 * 24 * 3600
FACTS_VALIDATOR = {
    "$jsonSchema": {
        "bsonType": "object",
        "required": ["_id", "patientId", "specialty", "channel", "ageBand", "bookedAt", "startAt", "outcome"],
        "properties": {
            "ageBand": {"bsonType": "string", "pattern": "^([0-9]{1,3}-[0-9]{1,3}|unknown)$"},
            "channel": {"enum": ["web", "chatbot", "staff"]},
            "outcome": {"enum": ["pending", "fulfilled", "noshow", "cancelled"]},
            "bookedAt": {"bsonType": "date"},
            "startAt": {"bsonType": "date"},
        },
    }
}


def ensure_schema(db: Database[dict[str, Any]]) -> None:
    names = set(db.list_collection_names())
    if "appointment_facts" not in names:
        try:
            db.create_collection("appointment_facts", validator=FACTS_VALIDATOR)
        except CollectionInvalid:
            pass
    db.appointment_facts.create_index([("startAt", ASCENDING)])
    db.appointment_facts.create_index([("patientId", ASCENDING), ("startAt", DESCENDING)])
    if "predictions" not in names:
        try:
            db.create_collection("predictions")
        except CollectionInvalid:
            pass
    info = db.command("listCollections", filter={"name": "predictions"})["cursor"]["firstBatch"]
    timeseries = bool(info and info[0].get("type") == "timeseries")
    db.predictions.create_index([("modelVersion", ASCENDING), ("predictedAt", ASCENDING)])
    db.predictions.create_index([("appointmentId", ASCENDING)])
    if not timeseries:
        db.predictions.create_index(
            [("predictedAt", ASCENDING)], expireAfterSeconds=PREDICTION_TTL_SECONDS, name="ttl_predictedAt"
        )
        db.predictions.create_index(
            [("appointmentId", ASCENDING), ("shadow", ASCENDING), ("modelVersion", ASCENDING)],
            unique=True,
            name="ux_prediction",
        )
    ensure_messaging_indexes(db)
