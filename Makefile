.PHONY: test format-check backend-test frontend-test backend-format compose-config up down

test: backend-test frontend-test

format-check:
	cd backend && ./mvnw spotless:check checkstyle:check -DskipTests
	cd frontend && npm run format:check && npm run lint
	test -z "$$(gofmt -l sandbox-controller)"
	bash scripts/verify-source-size.sh

backend-test:
	cd backend && ./mvnw --batch-mode --no-transfer-progress verify

frontend-test:
	cd frontend && npm test && npm run build

backend-format:
	cd backend && ./mvnw spotless:apply

compose-config:
	docker compose config --quiet

up:
	docker compose up -d --build

down:
	docker compose down
