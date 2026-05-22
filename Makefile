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

# ── Build ─────────────────────────────────────────────────────────────────────

build:
	$(MVN) compile

# ── Emulator: plain start / stop ──────────────────────────────────────────────

run:
	$(MVN) quarkus:dev -Dno-color > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@echo "Waiting for emulator to start on port $(PORT)..."
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up!"

stop:
	@if [ -f $(PID_FILE) ]; then \
		kill $$(cat $(PID_FILE)) 2>/dev/null || true; \
		rm $(PID_FILE); \
	fi
	@kill $$(lsof -ti :$(PORT) -P 2>/dev/null) 2>/dev/null || true
	@until ! lsof -ti :$(PORT) -P > /dev/null 2>&1; do sleep 1; done
	@echo "Emulator stopped."

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

test-servicebus-compat:
	@echo "==> Service Bus Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -Dtest=ServiceBusCompatibilityTest -q

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
	@echo "==> Building test images..."
	@docker build -q --platform linux/amd64 -t floci-az-compat-python -f $(PYTHON_DIR)/Dockerfile $(PYTHON_DIR)/
	@docker build -q -t floci-az-compat-node -f $(NODE_DIR)/Dockerfile $(NODE_DIR)/
	@docker build -q -t floci-az-compat-java -f $(JAVA_DIR)/Dockerfile $(JAVA_DIR)/
	@echo "==> Python SDK tests (blob, queue, table, appconfig, keyvault, eventhub)"
	docker run --rm --platform linux/amd64 --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
		-e EVENTHUB_AMQPS_PORT=5671 \
		floci-az-compat-python
	@echo "==> Node.js SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
		-e EVENTHUB_AMQP_PORT=5672 \
		floci-az-compat-node
	@echo "==> Java SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		-e SERVICEBUS_HOST=floci-az-servicebus-default \
		-e SERVICEBUS_AMQPS_PORT=5671 \
		-e SERVICEBUS_NAMESPACE=default \
		-v /var/run/docker.sock:/var/run/docker.sock \
		floci-az-compat-java

test: build
	$(MVN) test
	$(MAKE) run
	$(MAKE) test-python
	$(MAKE) test-java-compat
	$(MAKE) test-node-compat
	$(MAKE) stop

# ── Cleanup ───────────────────────────────────────────────────────────────────

clean:
	$(MVN) clean
	rm -rf $(PYTHON_DIR)/venv $(PYTHON_DIR)/tests/__pycache__
	rm -rf $(NODE_DIR)/node_modules $(NODE_DIR)/dist
	rm -f emulator.log $(PID_FILE)
