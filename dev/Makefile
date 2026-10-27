# Bộ công cụ phát triển cục bộ trên Kubernetes nhỏ (kind). Chạy từ thư mục gốc: make -C dev <target>
SHELL := /usr/bin/env bash
S := scripts

.PHONY: help doctor up down status env seed-users token psql mongosh redis-cli logs

help: ## Liệt kê lệnh
	@awk -F':.*## ' '/^[a-z-]+:.*## / {printf "  %-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

doctor: ## Kiểm tra máy đủ điều kiện (docker, kind, kubectl, helm, RAM, cổng)
	@$(S)/doctor.sh

up: ## Dựng cụm kind và cài phụ thuộc (PROFILE=core)
	@$(S)/up.sh

down: ## Xóa cụm local
	@$(S)/down.sh

status: ## Xem trạng thái pod
	@kubectl --context kind-shp-local -n shp-deps get pods,svc

env: ## Ghi lại dev/.local/connection.env
	@$(S)/env.sh

seed-users: ## Tạo người dùng thử (dữ liệu giả) trong Keycloak
	@$(S)/seed-users.sh

token: ## In access token: make token USER=doctor.demo
	@$(S)/token.sh $(USER)

psql: ## Mở psql: make psql DB=scheduling
	@kubectl --context kind-shp-local -n shp-deps exec -it statefulset/postgres -- psql -U shp -d $(or $(DB),scheduling)

mongosh: ## Mở mongosh
	@kubectl --context kind-shp-local -n shp-deps exec -it statefulset/mongodb -- mongosh

redis-cli: ## Mở redis-cli
	@kubectl --context kind-shp-local -n shp-deps exec -it deploy/redis -- sh -c 'redis-cli -a "$$REDIS_PASSWORD" --no-auth-warning'

logs: ## Xem log: make logs APP=keycloak
	@kubectl --context kind-shp-local -n shp-deps logs -f deploy/$(or $(APP),keycloak)
