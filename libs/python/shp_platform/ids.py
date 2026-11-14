"""UUIDv7 (RFC 9562): event and record ids sort by time, like org.shp.events.Uuids."""

import secrets
import time
import uuid


def uuid7(now_ms: int | None = None) -> uuid.UUID:
    ms = int(time.time() * 1000) if now_ms is None else now_ms
    rand = secrets.randbits(74)
    value = (ms & ((1 << 48) - 1)) << 80
    value |= 0x7 << 76  # version
    value |= ((rand >> 62) & 0xFFF) << 64  # rand_a (12 bit)
    value |= 0b10 << 62  # variant
    value |= rand & ((1 << 62) - 1)  # rand_b (62 bit)
    return uuid.UUID(int=value)
