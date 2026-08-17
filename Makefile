.PHONY: bootstrap up down logs verify secrets generate

bootstrap:
	./scripts/bootstrap-local.sh

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f app

verify:
	./mvnw verify

secrets:
	docker run --rm -v "$(CURDIR):/repo:ro" aquasec/trivy:0.58.2 fs --exit-code 1 --scanners secret /repo

generate:
	./trigger-generation.sh --run
