"""Hội thoại, tin nhắn, phản hồi (AI-12; 06 §2). TTL 30 ngày cho hội thoại và tin nhắn; feedback giữ lâu để làm eval.

Nội dung câu hỏi lưu ở dạng đã che PII (đúng như văn bản rời cụm). Chỉ chủ hội thoại đọc được.
"""

import uuid
from datetime import UTC, datetime
from typing import Any

from pymongo import ASCENDING, DESCENDING
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from shp_platform.errors import ShpError

TTL = 30 * 24 * 3600
Doc = dict[str, Any]


def ensure_indexes(db: Database[Doc]) -> None:
    db.conversations.create_index([("userSubject", ASCENDING), ("lastMessageAt", DESCENDING)])
    db.conversations.create_index([("lastMessageAt", ASCENDING)], expireAfterSeconds=TTL, name="ttl_conversation")
    db.messages.create_index([("conversationId", ASCENDING), ("createdAt", ASCENDING)])
    db.messages.create_index([("createdAt", ASCENDING)], expireAfterSeconds=TTL, name="ttl_message")
    db.feedback.create_index([("messageId", ASCENDING), ("userSubject", ASCENDING)], unique=True)


def open_conversation(db: Database[Doc], subject: str, conversation_id: str | None, kind: str) -> str:
    now = datetime.now(UTC)
    if conversation_id:
        conv = db.conversations.find_one({"_id": conversation_id})
        if conv is None or conv["userSubject"] != subject or conv["kind"] != kind:
            raise ShpError("not-found")  # không lộ hội thoại của người khác
        db.conversations.update_one({"_id": conversation_id}, {"$set": {"lastMessageAt": now}})
        return conversation_id
    cid = str(uuid.uuid4())
    db.conversations.insert_one(
        {"_id": cid, "userSubject": subject, "kind": kind, "createdAt": now, "lastMessageAt": now}
    )
    return cid


def add_message(db: Database[Doc], conversation_id: str, role: str, content: str, **fields: Any) -> str:
    mid = str(uuid.uuid4())
    db.messages.insert_one(
        {
            "_id": mid,
            "conversationId": conversation_id,
            "role": role,
            "content": content,
            "createdAt": datetime.now(UTC),
            **fields,
        }
    )
    return mid


def _owned_message(db: Database[Doc], subject: str, message_id: str) -> Doc:
    msg = db.messages.find_one({"_id": message_id, "role": "assistant"})
    conv = db.conversations.find_one({"_id": msg["conversationId"]}) if msg else None
    if msg is None or conv is None or conv["userSubject"] != subject:
        raise ShpError("not-found")
    return msg


def feedback(db: Database[Doc], subject: str, message_id: str, rating: str, comment: str | None, replace: bool) -> None:
    _owned_message(db, subject, message_id)
    doc = {
        "messageId": message_id,
        "userSubject": subject,
        "rating": rating,
        "comment": comment,
        "updatedAt": datetime.now(UTC),
    }
    if replace:
        if db.feedback.update_one({"messageId": message_id, "userSubject": subject}, {"$set": doc}).matched_count == 0:
            raise ShpError("not-found")
        return
    try:
        db.feedback.insert_one({"_id": str(uuid.uuid4()), **doc, "createdAt": doc["updatedAt"]})
    except DuplicateKeyError as exc:
        raise ShpError("invalid-state-transition", "Đã đánh giá câu trả lời này; dùng PUT để sửa.") from exc


def list_conversations(db: Database[Doc], subject: str, kind: str | None) -> list[Doc]:
    q: Doc = {"userSubject": subject}
    if kind:
        q["kind"] = kind
    return [
        {
            "id": c["_id"],
            "kind": c["kind"],
            "createdAt": c["createdAt"].isoformat(),
            "lastMessageAt": c["lastMessageAt"].isoformat(),
        }
        for c in db.conversations.find(q).sort("lastMessageAt", -1).limit(50)
    ]


def get_conversation(db: Database[Doc], subject: str, conversation_id: str) -> Doc:
    conv = db.conversations.find_one({"_id": conversation_id})
    if conv is None or conv["userSubject"] != subject:
        raise ShpError("not-found")
    msgs = db.messages.find({"conversationId": conversation_id}).sort("createdAt", 1)
    return {
        "id": conversation_id,
        "kind": conv["kind"],
        "messages": [
            {
                "id": m["_id"],
                "role": m["role"],
                "content": m["content"],
                "citations": m.get("citations", []),
                "createdAt": m["createdAt"].isoformat(),
            }
            for m in msgs
        ],
    }
