# syntax=docker/dockerfile:1
FROM mirror.gcr.io/library/python:3.15-slim AS build
WORKDIR /src
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir --prefix=/install .

FROM mirror.gcr.io/library/python:3.15-slim
RUN useradd --uid 10001 --no-create-home app
COPY --from=build /install /usr/local
USER 10001
EXPOSE 8080
ENTRYPOINT ["uvicorn", "shp_ai.main:app", "--host", "0.0.0.0", "--port", "8080"]
