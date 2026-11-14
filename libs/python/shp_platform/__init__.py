"""Shared library for Python services (ai, ml). Owner: SA.

The single source lives in libs/python/shp_platform; tools/pylib/sync.py copies it into
services/<svc>/src/shp_platform (each service's build context is only the service directory).
Do not edit the copy inside a service: CI checks it with `sync.py --check`.
Rules match the Java version (libs/java): roles, staff MFA, error catalog, audit, CloudEvents.
"""

__version__ = "0.1.0"
