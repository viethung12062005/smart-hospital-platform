# syntax=docker/dockerfile:1
FROM mirror.gcr.io/library/python:3.15-slim AS build
WORKDIR /src
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir --prefix=/install .

FROM mirror.gcr.io/library/python:3.15-slim
RUN useradd --uid 10001 --no-create-home app
# pip/setuptools are not needed at runtime and their vendored libs (urllib3, msgpack) carry unpatched CVEs: remove them.
# Pin the version (hadolint DL3008); when the base image changes, refresh it with `apt-cache policy <package>`.
# zlib1g: patches CVE-2026-76844 and CVE-2026-85091 (HIGH) that the base image does not have yet; the image scan blocks HIGH findings that have a fix.
RUN apt-get update && apt-get install -y --no-install-recommends zlib1g=1:1.3.dfsg+really1.3.1-1+deb13u1 \
    && rm -rf /var/lib/apt/lists/*
RUN pip uninstall -y pip setuptools wheel
COPY --from=build /install /usr/local
USER 10001
EXPOSE 8080
ENTRYPOINT ["uvicorn", "shp_ai.main:app", "--host", "0.0.0.0", "--port", "8080"]
