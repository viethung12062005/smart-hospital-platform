"""`pending_actions` (06 §2): proposed booking/cancel waiting for the user to confirm in the UI (AC-14.1). TTL 5
minutes."""

import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

from pymongo import ASCENDING
from pymongo.database import Database

from shp_platform.errors import ShpError

TTL = timedelta(minutes=5)
BOOK = "book_appointment"
CANCEL = "cancel_appointment"
Doc = dict[str, Any]


def ensure_indexes(db: Database[Doc]) -> None:
    db.pending_actions.create_index(
        [("expiresAt", ASCENDING)], expireAfterSeconds=0
    )  # expiresAt is an absolute timestamp


def create(
    db: Database[Doc], conversation_id: str, tool: str, arguments: dict[str, Any], now: datetime | None = None
) -> Doc:
    now = now or datetime.now(UTC)
    doc = {
        "_id": str(uuid.uuid4()),
        "conversationId": conversation_id,
        "tool": tool,
        "arguments": arguments,
        "createdAt": now,
        "expiresAt": now + TTL,
        "confirmedAt": None,
    }
    db.pending_actions.insert_one(doc)
    return doc


def get_for_confirm(db: Database[Doc], action_id: str, conversation_id: str, now: datetime | None = None) -> Doc:
    """403 if not the conversation owner; 422 confirmation-expired if older than 5 minutes or already confirmed."""
    doc = db.pending_actions.find_one({"_id": action_id})
    if doc is None:
        raise ShpError("not-found")
    if doc["conversationId"] != conversation_id:
        raise ShpError("forbidden")
    now = now or datetime.now(UTC)
    expires = doc["expiresAt"] if doc["expiresAt"].tzinfo else doc["expiresAt"].replace(tzinfo=UTC)
    if doc["confirmedAt"] is not None or now >= expires:
        raise ShpError("confirmation-expired")
    return doc


def mark_confirmed(db: Database[Doc], action_id: str, now: datetime | None = None) -> None:
    db.pending_actions.update_one({"_id": action_id}, {"$set": {"confirmedAt": now or datetime.now(UTC)}})
