# Local development toolkit on a small Kubernetes cluster (kind). Run from the repo root: make -C dev <target>
SHELL := /usr/bin/env bash
S := scripts
export PATH := $(CURDIR)/.local/bin:$(PATH)
APPS := shp-apps

.PHONY: pull-models help tools quickstart doctor up down status env seed-users token deploy undeploy apps app-logs port-forward psql mongosh redis-cli logs

help: ## List commands
	@awk -F':.*## ' '/^[a-z-]+:.*## / {printf "  %-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

tools: ## Install kind, kubectl, helm into dev/.local/bin (no sudo required)
	@$(S)/install-tools.sh

quickstart: ## One command: install tools → create cluster → test users → deploy all services
	@$(S)/install-tools.sh && $(S)/up.sh && $(S)/seed-users.sh && $(S)/deploy.sh all

doctor: ## Check the machine is ready (docker, kind, kubectl, helm, RAM, ports)
	@$(S)/doctor.sh

up: ## Create the kind cluster and install dependencies (PROFILE=core)
	@$(S)/up.sh

pull-models: ## (full profile) Pull the bge-m3 and qwen2.5 models into Ollama
	@for m in bge-m3 qwen2.5:3b; do kubectl --context kind-shp-local -n shp-deps exec statefulset/ollama -- ollama pull $$m; done

down: ## Delete the local cluster
	@$(S)/down.sh

status: ## Show pod status
	@kubectl --context kind-shp-local -n shp-deps get pods,svc

env: ## Rewrite dev/.local/connection.env
	@$(S)/env.sh

seed-users: ## Create test users (fake data) in Keycloak
	@$(S)/seed-users.sh

token: ## Print an access token: make token USER=doctor.demo
	@$(S)/token.sh $(USER)

deploy: ## Build + deploy a service to kind: make deploy SVC=scheduling (or SVC=all)
	@$(S)/deploy.sh $(SVC)

undeploy: ## Remove a service: make undeploy SVC=scheduling
	@helm --kube-context kind-shp-local -n $(APPS) uninstall $(SVC)

apps: ## Status of the application services
	@kubectl --context kind-shp-local -n $(APPS) get pods,svc

app-logs: ## Service logs: make app-logs SVC=scheduling
	@kubectl --context kind-shp-local -n $(APPS) logs -f deploy/$(SVC)

port-forward: ## Expose the gateway at http://localhost:18000
	@kubectl --context kind-shp-local -n $(APPS) port-forward svc/gateway 18000:80

psql: ## Open psql: make psql DB=scheduling
	@kubectl --context kind-shp-local -n shp-deps exec -it statefulset/postgres -- psql -U shp -d $(or $(DB),scheduling)

mongosh: ## Open mongosh
	@kubectl --context kind-shp-local -n shp-deps exec -it statefulset/mongodb -- mongosh

redis-cli: ## Open redis-cli
	@kubectl --context kind-shp-local -n shp-deps exec -it deploy/redis -- sh -c 'redis-cli -a "$$REDIS_PASSWORD" --no-auth-warning'

logs: ## View logs: make logs APP=keycloak
	@kubectl --context kind-shp-local -n shp-deps logs -f deploy/$(or $(APP),keycloak)
