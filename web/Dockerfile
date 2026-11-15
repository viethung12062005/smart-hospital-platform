# syntax=docker/dockerfile:1
# Build context = repo root (needs contracts/terminology):  docker build -f web/Dockerfile .
FROM mirror.gcr.io/library/node:26-alpine AS build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY contracts/terminology /src/contracts/terminology
COPY web/ ./
# The real build does not include the mock server (MSW is only loaded in mock mode); also drop its service worker file.
RUN npm run build && rm -f dist/mockServiceWorker.js

FROM mirror.gcr.io/library/nginx:1.31-alpine
# Patch Alpine packages with known bugs that already have fixes (Trivy blocks HIGH/CRITICAL), remove the unused curl,
# and drop the default entrypoint (needs write access to /etc/nginx) so it can run as a non-root user.
# hadolint ignore=DL3017,DL3018
RUN apk upgrade --no-cache \
 && apk del --no-cache curl \
 && rm -rf /docker-entrypoint.d /docker-entrypoint.sh /etc/nginx/conf.d
COPY web/nginx.conf /etc/nginx/nginx.conf
COPY web/nginx-security-headers.conf /etc/nginx/shp-security-headers.conf
COPY --from=build /src/web/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
