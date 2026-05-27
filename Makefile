.PHONY: build run run-cosmos-mongo run-cosmos-postgresql run-cosmos-cassandra run-cosmos-gremlin run-cosmos-table run-cosmos-nosql run-sql stop \
        test test-python test-java-compat test-node-compat test-servicebus-compat test-appconfig test-cosmos \
        test-cosmos-mongo test-cosmos-postgresql test-cosmos-cassandra test-cosmos-gremlin test-cosmos-table test-cosmos-nosql test-cosmos-all \
        test-sql compat-docker clean

MVN            = ./mvnw
PORT           = 4577
PID_FILE       = emulator.pid
PYTHON_DIR     = compatibility-tests/sdk-test-python
JAVA_DIR       = compatibility-tests/sdk-test-java
NODE_DIR       = compatibility-tests/sdk-test-node
COMPAT_NETWORK = compat-net
COMPAT_RESULTS = test-results
FLOCI_AZ_IMAGE = floci-az:test
PYTHON_IMAGE   = compat-sdk-test-python
JAVA_IMAGE     = compat-sdk-test-java
NODE_IMAGE     = compat-sdk-test-node

# ── Build ─────────────────────────────────────────────────────────────────────

build:
	$(MVN) compile

# ── Emulator: plain start / stop ──────────────────────────────────────────────

run:
	$(MVN) quarkus:dev -Dno-color \
		"-Dfloci-az.tls.enabled=true" \
		> emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@echo "Waiting for emulator to start on port $(PORT)..."
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up!"

run-docker:
	docker compose up -d --build floci-az
	@echo "Waiting for Docker emulator to start on port $(PORT)..."
	@until curl -s http://127.0.0.1:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Docker emulator is up!"

stop:
	@if [ -f $(PID_FILE) ]; then \
		kill $$(cat $(PID_FILE)) 2>/dev/null || true; \
		rm $(PID_FILE); \
	fi
	@kill $$(lsof -ti :$(PORT) -P 2>/dev/null) 2>/dev/null || true
	@until ! lsof -ti :$(PORT) -P > /dev/null 2>&1; do sleep 1; done
	@echo "Emulator stopped."

stop-docker:
	docker compose down

require-emulator:
	@curl -s http://127.0.0.1:$(PORT)/health > /dev/null || \
		(echo "floci-az emulator is not reachable at http://127.0.0.1:$(PORT). Run 'make run' first, or use 'make test-blob' to start and stop it automatically."; exit 1)

# ── Compatibility test environment (mirrors .github/workflows/compatibility.yml) ──

compat-network:
	@docker network inspect $(COMPAT_NETWORK) >/dev/null 2>&1 || docker network create $(COMPAT_NETWORK)

compat-build:
	$(MVN) clean package -DskipTests -q
	docker build -f docker/Dockerfile.jvm-package -t $(FLOCI_AZ_IMAGE) .

compat-run: compat-network
	@docker rm -f floci-az >/dev/null 2>&1 || true
	docker run -d --name floci-az --network $(COMPAT_NETWORK) \
		-v /var/run/docker.sock:/var/run/docker.sock \
		-p $(PORT):4577 \
		-e FLOCI_AZ_SERVICES_DOCKER_NETWORK=$(COMPAT_NETWORK) \
		$(FLOCI_AZ_IMAGE)
	@echo "Waiting for floci-az Docker emulator to start on port $(PORT)..."
	@until curl -sf http://127.0.0.1:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "floci-az Docker emulator is up!"

compat-stop:
	@docker rm -f floci-az >/dev/null 2>&1 || true
	@docker network rm $(COMPAT_NETWORK) >/dev/null 2>&1 || true

compat-python-image:
	docker build -t $(PYTHON_IMAGE) -f $(PYTHON_DIR)/Dockerfile $(PYTHON_DIR)/

compat-java-image:
	docker build -t $(JAVA_IMAGE) -f $(JAVA_DIR)/Dockerfile $(JAVA_DIR)/

compat-node-image:
	docker build -t $(NODE_IMAGE) -f $(NODE_DIR)/Dockerfile $(NODE_DIR)/

# ── Emulator: start with a specific Cosmos engine enabled ─────────────────────

run-cosmos-mongo:
	$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.mongodb.enabled=true" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (MongoDB engine enabled)"

run-cosmos-postgresql:
	$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.postgresql.enabled=true" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (PostgreSQL engine enabled)"

run-cosmos-cassandra:
	$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.cassandra.enabled=true" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (Cassandra engine enabled)"

run-cosmos-gremlin:
	$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.gremlin.enabled=true" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (Gremlin engine enabled)"

run-cosmos-table:
	$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.table.enabled=true" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (Table engine enabled)"

run-cosmos-nosql:
	$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.nosql.enabled=true" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (NoSQL engine enabled)"

run-sql:
	$(MVN) quarkus:dev -Dno-color \
		"-Dfloci-az.services.sql.accept-eula=Y" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (SQL service — EULA accepted)"

# ── Standard SDK compatibility tests ──────────────────────────────────────────

test-python:
	@echo "==> Python SDK compatibility tests (all services)"
	@cd $(PYTHON_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv; fi && \
	./venv/bin/pip install -q -r requirements.txt && \
	./venv/bin/pytest tests/ -v

test-java-compat:
	@echo "==> Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -q

test-node-compat:
	@echo "==> Node.js SDK compatibility tests"
	@cd $(NODE_DIR) && \
	npm install --silent && \
	npm test

test-python:
	@echo "==> Python SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/python
	$(MAKE) compat-build
	$(MAKE) compat-run
	$(MAKE) compat-python-image; \
	EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
			-e EVENTHUB_AMQPS_PORT=5671 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/python:/results" \
			$(PYTHON_IMAGE); \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-java-compat:
	@echo "==> Java SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/java
	$(MAKE) compat-build
	$(MAKE) compat-run
	$(MAKE) compat-java-image; \
	EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e SERVICEBUS_HOST=floci-az-servicebus-default \
			-e SERVICEBUS_AMQPS_PORT=5671 \
			-e SERVICEBUS_NAMESPACE=default \
			-v /var/run/docker.sock:/var/run/docker.sock \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/java:/results" \
			$(JAVA_IMAGE); \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-node-compat:
	@echo "==> Node.js SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/node
	$(MAKE) compat-build
	$(MAKE) compat-run
	$(MAKE) compat-node-image; \
	EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
			-e EVENTHUB_AMQP_PORT=5672 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/node:/results" \
			$(NODE_IMAGE); \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-servicebus-compat:
	@echo "==> Service Bus Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -Dtest=ServiceBusCompatibilityTest -q

test-blob-python-local: require-emulator
	@echo "==> Blob Storage Python SDK compatibility tests"
	@cd $(PYTHON_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv; fi && \
	./venv/bin/pip install -q -r requirements.txt && \
	FLOCI_AZ_ENDPOINT=http://127.0.0.1:$(PORT) ./venv/bin/pytest -q tests/test_blob.py

test-blob-java-local: require-emulator
	@echo "==> Blob Storage Java SDK compatibility tests"
	cd $(JAVA_DIR) && FLOCI_AZ_ENDPOINT=http://127.0.0.1:$(PORT) mvn test -Dtest=BlobCompatibilityTest

test-blob-node-local: require-emulator
	@echo "==> Blob Storage Node.js SDK compatibility tests"
	@cd $(NODE_DIR) && \
	npm install --silent && \
	FLOCI_AZ_ENDPOINT=http://127.0.0.1:$(PORT) npm test -- --runTestsByPath tests/blob.test.ts

test-blob-python:
	@echo "==> Blob Storage Python SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/blob-python
	$(MAKE) compat-build
	$(MAKE) compat-run
	$(MAKE) compat-python-image; \
	EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/blob-python:/results" \
			--entrypoint pytest \
			$(PYTHON_IMAGE) tests/test_blob.py -q --junit-xml=/results/junit.xml; \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-blob-java:
	@echo "==> Blob Storage Java SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/blob-java
	$(MAKE) compat-build
	$(MAKE) compat-run
	$(MAKE) compat-java-image; \
	EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/blob-java:/results" \
			--entrypoint bash \
			$(JAVA_IMAGE) -c 'mvn test -Dtest=BlobCompatibilityTest; status=$$?; cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; exit $$status'; \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-blob-node:
	@echo "==> Blob Storage Node.js SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/blob-node
	$(MAKE) compat-build
	$(MAKE) compat-run
	$(MAKE) compat-node-image; \
	EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e JEST_JUNIT_OUTPUT_DIR=/results \
			-e JEST_JUNIT_OUTPUT_NAME=junit.xml \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/blob-node:/results" \
			--entrypoint npm \
			$(NODE_IMAGE) test -- --runTestsByPath tests/blob.test.ts; \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-blob-local:
	@echo "==> Blob Storage SDK compatibility tests"
	$(MAKE) run
	$(MAKE) test-blob-python-local; \
	EXIT=$$?; if [ $$EXIT -eq 0 ]; then $(MAKE) test-blob-node-local; EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) test-blob-java-local; EXIT=$$?; fi; \
	$(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-blob:
	@echo "==> Blob Storage SDK compatibility tests (Docker)"
	@mkdir -p $(COMPAT_RESULTS)/blob-python $(COMPAT_RESULTS)/blob-node $(COMPAT_RESULTS)/blob-java
	$(MAKE) compat-build
	$(MAKE) compat-run
	@EXIT=0; \
	$(MAKE) compat-python-image || EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-node-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-java-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/blob-python:/results" \
			--entrypoint pytest \
			$(PYTHON_IMAGE) tests/test_blob.py -q --junit-xml=/results/junit.xml; \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e JEST_JUNIT_OUTPUT_DIR=/results \
			-e JEST_JUNIT_OUTPUT_NAME=junit.xml \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/blob-node:/results" \
			--entrypoint npm \
			$(NODE_IMAGE) test -- --runTestsByPath tests/blob.test.ts; \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/blob-java:/results" \
			--entrypoint bash \
			$(JAVA_IMAGE) -c 'mvn test -Dtest=BlobCompatibilityTest; status=$$?; cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; exit $$status'; \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-cosmos:
	@echo "==> Cosmos DB NoSQL (in-memory) compatibility tests"
	@cd $(PYTHON_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv; fi && \
	./venv/bin/pip install -q -r requirements.txt && \
	./venv/bin/pytest tests/test_cosmos.py -v
	@echo "==> Cosmos DB NoSQL (in-memory) compatibility tests (Java)"
	@cd $(JAVA_DIR) && mvn test -Dtest=CosmosCompatibilityTest -q
	@echo "==> Cosmos DB NoSQL (in-memory) compatibility tests (Node)"
	@cd $(NODE_DIR) && \
	npm install --silent && \
	npx jest cosmos.test --testTimeout=30000

# ── Cosmos engine tests — one target per API ──────────────────────────────────
# Each target: starts the emulator with that engine enabled, runs the test, stops.
# Requires Docker.

test-cosmos-mongo:
	@echo "==> Cosmos MongoDB engine test"
	$(MAKE) run-cosmos-mongo
	cd $(JAVA_DIR) && mvn test -Dtest=CosmosMongoEngineCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-cosmos-postgresql:
	@echo "==> Cosmos PostgreSQL engine test"
	$(MAKE) run-cosmos-postgresql
	cd $(JAVA_DIR) && mvn test -Dtest=CosmosPostgresEngineCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-cosmos-cassandra:
	@echo "==> Cosmos Cassandra engine test (ScyllaDB may take ~60s to boot)"
	$(MAKE) run-cosmos-cassandra
	cd $(JAVA_DIR) && mvn test -Dtest=CosmosCassandraEngineCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-cosmos-gremlin:
	@echo "==> Cosmos Gremlin engine test"
	$(MAKE) run-cosmos-gremlin
	cd $(JAVA_DIR) && mvn test -Dtest=CosmosGremlinEngineCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-cosmos-table:
	@echo "==> Cosmos Table engine test"
	$(MAKE) run-cosmos-table
	cd $(JAVA_DIR) && mvn test -Dtest=CosmosTableEngineCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-cosmos-nosql:
	@echo "==> Cosmos NoSQL engine test (embedded)"
	$(MAKE) run-cosmos-nosql
	cd $(JAVA_DIR) && mvn test -Dtest=CosmosNoSqlEngineCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

test-cosmos-all:
	@echo "==> All Cosmos engine tests (runs one by one, requires Docker)"
	$(MAKE) test-cosmos-mongo
	$(MAKE) test-cosmos-postgresql
	$(MAKE) test-cosmos-cassandra
	$(MAKE) test-cosmos-gremlin
	$(MAKE) test-cosmos-table
	$(MAKE) test-cosmos-nosql

test-sql:
	@echo "==> Azure SQL Database compatibility test (requires Docker)"
	$(MAKE) run-sql
	cd $(JAVA_DIR) && mvn test -Dtest=SqlCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

# ── Full test suite ────────────────────────────────────────────────────────────

# Run all compatibility tests in Docker containers against the running floci-az.
# Requires: docker compose up -d
compat-docker:
	$(MAKE) compat-build
	$(MAKE) compat-run
	@mkdir -p $(COMPAT_RESULTS)/python $(COMPAT_RESULTS)/node $(COMPAT_RESULTS)/java
	@EXIT=0; \
	$(MAKE) compat-python-image || EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-node-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-java-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Python SDK tests (blob, queue, table, appconfig, keyvault, eventhub)"; \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
			-e EVENTHUB_AMQPS_PORT=5671 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/python:/results" \
			$(PYTHON_IMAGE); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Node.js SDK tests"; \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
			-e EVENTHUB_AMQP_PORT=5672 \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/node:/results" \
			$(NODE_IMAGE); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Java SDK tests"; \
		docker run --rm --network $(COMPAT_NETWORK) \
			-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
			-e SERVICEBUS_HOST=floci-az-servicebus-default \
			-e SERVICEBUS_AMQPS_PORT=5671 \
			-e SERVICEBUS_NAMESPACE=default \
			-v /var/run/docker.sock:/var/run/docker.sock \
			-v "$(CURDIR)/$(COMPAT_RESULTS)/java:/results" \
			$(JAVA_IMAGE); \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-compat:
	@echo "==> Running all SDK compatibility tests (emulator must be running)"
	$(MAKE) test-python
	$(MAKE) test-java-compat
	$(MAKE) test-node-compat

test: build
	$(MVN) test
	$(MAKE) run
	$(MAKE) test-compat
	$(MAKE) stop

# ── Cleanup ───────────────────────────────────────────────────────────────────

clean:
	$(MVN) clean
	rm -rf $(PYTHON_DIR)/venv $(PYTHON_DIR)/tests/__pycache__
	rm -rf $(NODE_DIR)/node_modules $(NODE_DIR)/dist
	rm -f emulator.log $(PID_FILE)
