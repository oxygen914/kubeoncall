.PHONY: test backend-test frontend-test backend-format compose-config up down

test: backend-test frontend-test

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
