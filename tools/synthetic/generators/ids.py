"""Deterministic UUIDv7 generator for synthetic data."""

import random
import uuid


class UUID7Generator:
    """Generate reproducible UUIDv7 identifiers from a seed."""

    def __init__(
        self,
        seed: int,
        start_timestamp_ms: int = 1767225600000,
    ) -> None:
        self._random = random.Random(seed)
        self._timestamp_ms = start_timestamp_ms

    def next(self) -> uuid.UUID:
        """Return the next deterministic UUIDv7."""
        timestamp = self._timestamp_ms
        self._timestamp_ms += 1

        rand = self._random.getrandbits(74)

        value = (timestamp & ((1 << 48) - 1)) << 80
        value |= 0x7 << 76
        value |= ((rand >> 62) & 0xFFF) << 64
        value |= 0b10 << 62
        value |= rand & ((1 << 62) - 1)

        return uuid.UUID(int=value)
