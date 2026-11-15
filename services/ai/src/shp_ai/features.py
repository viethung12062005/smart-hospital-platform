"""Import feature modules so they register their routers (main.EXTRA_ROUTERS) and consumers (wiring.STARTERS)."""

from shp_ai import (
    api_assistant,  # noqa: E402, F401 - AI1, AI2, AI3, AI10
    api_booking,  # noqa: E402, F401 - AI7, AI8
    api_summary,  # noqa: E402, F401 - AI4
)
