# syntax=docker/dockerfile:1
# Build context = gốc repo (cần contracts/terminology):  docker build -f web/Dockerfile .
FROM mirror.gcr.io/library/node:22-alpine AS build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY contracts/terminology /src/contracts/terminology
COPY web/ ./
# Bản build thật không chứa máy chủ giả (MSW chỉ nạp ở chế độ mock); bỏ luôn file service worker của nó.
RUN npm run build && rm -f dist/mockServiceWorker.js

FROM mirror.gcr.io/library/nginx:1.31-alpine
# Vá gói Alpine còn lỗi đã có bản sửa (Trivy chặn HIGH/CRITICAL), gỡ curl không dùng lúc chạy,
# bỏ entrypoint mặc định (cần quyền ghi /etc/nginx) để chạy được với user không phải root.
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
