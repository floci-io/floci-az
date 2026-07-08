.PHONY: build run run-docker stop stop-docker require-emulator \
        compat-network compat-build compat-run compat-stop compat-python-image compat-java-image compat-node-image compat-terraform-image compat-opentofu-image compat-azcli-image \
        run-cosmos-mongo run-cosmos-postgresql run-cosmos-cassandra run-cosmos-gremlin run-cosmos-table run-cosmos-nosql run-sql \
        test test-python-compat test-python-compat-local test-java-compat test-java-compat-local test-node-compat test-node-compat-local test-servicebus-compat \
        test-blob test-blob-local test-blob-python test-blob-python-local test-blob-java test-blob-java-local test-blob-node test-blob-node-local \
        test-apim-java \
        test-cosmos test-cosmos-mongo test-cosmos-postgresql test-cosmos-cassandra test-cosmos-gremlin test-cosmos-table test-cosmos-nosql test-cosmos-all \
        test-sql test-terraform-compat test-opentofu-compat test-azcli test-iac-compat compat-docker test-compat clean

MVN            = ./mvnw
PORT           = 4577
PID_FILE       = emulator.pid
PYTHON_DIR     = compatibility-tests/sdk-test-python
JAVA_DIR       = compatibility-tests/sdk-test-java
NODE_DIR       = compatibility-tests/sdk-test-node
TERRAFORM_DIR  = compatibility-tests/compat-terraform
OPENTOFU_DIR   = compatibility-tests/compat-opentofu
AZCLI_DIR      = compatibility-tests/compat-azcli
COMPAT_NETWORK = compat-net
COMPAT_RESULTS = test-results
FLOCI_AZ_IMAGE = floci-az:test
PYTHON_IMAGE   = compat-sdk-test-python
JAVA_IMAGE     = compat-sdk-test-java
NODE_IMAGE     = compat-sdk-test-node
TERRAFORM_IMAGE = compat-terraform
OPENTOFU_IMAGE  = compat-opentofu
AZCLI_IMAGE     = compat-azcli

# ── Per-suite container env — single source of truth ─────────────────────────
# Every docker run for a suite (individual test-*-compat targets AND compat-docker)
# must use its SUITE_ENV_* variable; the CI matrix extra_env in
# .github/workflows/compatibility.yml mirrors these (see the sync table in AGENTS.md).
# Suite-internal defaults (FLOCI_AZ_ENDPOINT, EVENTHUB_*, JEST_JUNIT_*) are baked as
# ENV in each suite's Dockerfile — only network-topology overrides belong here.
SUITE_ENV_PYTHON =
SUITE_ENV_JAVA   = -e SERVICEBUS_HOST=floci-az-servicebus-default \
	-e SERVICEBUS_AMQPS_PORT=5671 \
	-e SERVICEBUS_NAMESPACE=default
SUITE_ENV_NODE   =

# Per-suite build context and image tag, keyed by suite name.
SUITES = python java node terraform opentofu azcli
SUITE_DIR_python    = $(PYTHON_DIR)
SUITE_DIR_java      = $(JAVA_DIR)
SUITE_DIR_node      = $(NODE_DIR)
SUITE_DIR_terraform = $(TERRAFORM_DIR)
SUITE_DIR_opentofu  = $(OPENTOFU_DIR)
SUITE_DIR_azcli     = $(AZCLI_DIR)
SUITE_IMAGE_python    = $(PYTHON_IMAGE)
SUITE_IMAGE_java      = $(JAVA_IMAGE)
SUITE_IMAGE_node      = $(NODE_IMAGE)
SUITE_IMAGE_terraform = $(TERRAFORM_IMAGE)
SUITE_IMAGE_opentofu  = $(OPENTOFU_IMAGE)
SUITE_IMAGE_azcli     = $(AZCLI_IMAGE)

# ── Canned recipes for Docker compat sessions ─────────────────────────────────
# One docker run for a suite container against the running emulator.
# $(call RUN_SUITE,<suite>,<results-subdir>,<env args>,<late args e.g. --entrypoint>,<container cmd>)
define RUN_SUITE
docker run --rm --network $(COMPAT_NETWORK) \
	-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
	$(3) \
	-v "$(CURDIR)/$(COMPAT_RESULTS)/$(2):/results" \
	$(4) \
	$(SUITE_IMAGE_$(1)) $(5)
endef

# Full session: build + start emulator, build suite image, run suite, always stop.
# $(call COMPAT_SESSION,<suite>,<results-subdir>,<env args>,<late args>,<container cmd>)
define COMPAT_SESSION
@mkdir -p $(COMPAT_RESULTS)/$(2)
$(MAKE) compat-build
$(MAKE) compat-run
@EXIT=0; \
$(MAKE) compat-$(1)-image || EXIT=$$?; \
if [ $$EXIT -eq 0 ]; then \
	$(call RUN_SUITE,$(1),$(2),$(3),$(4),$(5)); \
	EXIT=$$?; \
fi; \
$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT
endef

# ── Build ─────────────────────────────────────────────────────────────────────

build:
	$(MVN) compile

# ── Emulator: local start / stop ──────────────────────────────────────────────

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
		(echo "floci-az emulator is not reachable at http://127.0.0.1:$(PORT). Run 'make run' first, or use a Docker target such as 'make test-blob'."; exit 1)

# ── Compatibility environment — mirrors .github/workflows/compatibility.yml ──

compat-network:
	@docker network inspect $(COMPAT_NETWORK) >/dev/null 2>&1 || docker network create $(COMPAT_NETWORK)

# JVM image for the local dev loop; CI builds the native image via docker/Dockerfile.native-package.
compat-build:
	$(MVN) clean package -DskipTests -q
	docker build -f docker/Dockerfile.jvm-package -t $(FLOCI_AZ_IMAGE) .

compat-run: compat-network
	@docker rm -f floci-az >/dev/null 2>&1 || true
	docker run -d --name floci-az --network $(COMPAT_NETWORK) \
		-v /var/run/docker.sock:/var/run/docker.sock \
		-p $(PORT):4577 \
		-e FLOCI_AZ_SERVICES_DOCKER_NETWORK=$(COMPAT_NETWORK) \
		-e FLOCI_AZ_TLS_ENABLED=true \
		-e FLOCI_AZ_HOSTNAME=floci-az \
		$(FLOCI_AZ_IMAGE)
	@echo "Waiting for floci-az Docker emulator to start on port $(PORT)..."
	@until curl -sf http://127.0.0.1:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "floci-az Docker emulator is up!"

compat-stop:
	@docker rm -f floci-az >/dev/null 2>&1 || true
	@docker network rm $(COMPAT_NETWORK) >/dev/null 2>&1 || true

define IMAGE_RULE
compat-$(1)-image:
	docker build -t $$(SUITE_IMAGE_$(1)) -f $$(SUITE_DIR_$(1))/Dockerfile $$(SUITE_DIR_$(1))/
endef
$(foreach s,$(SUITES),$(eval $(call IMAGE_RULE,$(s))))

# ── Emulator: start with a specific Cosmos engine enabled ─────────────────────

COSMOS_ENGINES = mongo postgresql cassandra gremlin table nosql
COSMOS_PROP_mongo = mongodb
COSMOS_RUNLABEL_mongo = MongoDB
COSMOS_RUNLABEL_postgresql = PostgreSQL
COSMOS_RUNLABEL_cassandra = Cassandra
COSMOS_RUNLABEL_gremlin = Gremlin
COSMOS_RUNLABEL_table = Table
COSMOS_RUNLABEL_nosql = NoSQL
COSMOS_TEST_mongo = CosmosMongoEngineCompatibilityTest
COSMOS_TEST_postgresql = CosmosPostgresEngineCompatibilityTest
COSMOS_TEST_cassandra = CosmosCassandraEngineCompatibilityTest
COSMOS_TEST_gremlin = CosmosGremlinEngineCompatibilityTest
COSMOS_TEST_table = CosmosTableEngineCompatibilityTest
COSMOS_TEST_nosql = CosmosNoSqlEngineCompatibilityTest
COSMOS_TESTHDR_mongo = MongoDB engine test
COSMOS_TESTHDR_postgresql = PostgreSQL engine test
COSMOS_TESTHDR_cassandra = Cassandra engine test (ScyllaDB may take ~60s to boot)
COSMOS_TESTHDR_gremlin = Gremlin engine test
COSMOS_TESTHDR_table = Table engine test
COSMOS_TESTHDR_nosql = NoSQL engine test (embedded)

# run-cosmos-<engine> starts quarkus:dev with that engine enabled;
# test-cosmos-<engine> runs its Java suite against it and always stops.
define COSMOS_RULES
run-cosmos-$(1):
	$$(MVN) quarkus:dev -Dno-color "-Dfloci-az.services.cosmos.engines.$$(or $$(COSMOS_PROP_$(1)),$(1)).enabled=true" > emulator.log 2>&1 & echo $$$$! > $$(PID_FILE)
	@until curl -s http://localhost:$$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! ($$(COSMOS_RUNLABEL_$(1)) engine enabled)"

test-cosmos-$(1):
	@echo "==> Cosmos $$(COSMOS_TESTHDR_$(1))"
	$$(MAKE) run-cosmos-$(1)
	cd $$(JAVA_DIR) && mvn test -Dtest=$$(COSMOS_TEST_$(1)); \
	EXIT=$$$$?; $$(MAKE) -C $$(CURDIR) stop; exit $$$$EXIT
endef
$(foreach e,$(COSMOS_ENGINES),$(eval $(call COSMOS_RULES,$(e))))

run-sql:
	$(MVN) quarkus:dev -Dno-color \
		"-Dfloci-az.services.sql.accept-eula=Y" > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up! (SQL service — EULA accepted)"

# ── SDK compatibility tests — Docker by default ───────────────────────────────

test-python-compat:
	@echo "==> Python SDK compatibility tests (Docker)"
	$(call COMPAT_SESSION,python,python,$(SUITE_ENV_PYTHON),,)

test-java-compat:
	@echo "==> Java SDK compatibility tests (Docker)"
	$(call COMPAT_SESSION,java,java,$(SUITE_ENV_JAVA) -v /var/run/docker.sock:/var/run/docker.sock,,)

test-node-compat:
	@echo "==> Node.js SDK compatibility tests (Docker)"
	$(call COMPAT_SESSION,node,node,$(SUITE_ENV_NODE),,)

test-python-compat-local:
	@echo "==> Python SDK compatibility tests (all services, local emulator)"
	@cd $(PYTHON_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv; fi && \
	./venv/bin/pip install -q -r requirements.txt && \
	./venv/bin/pytest tests/ -v

test-java-compat-local:
	@echo "==> Java SDK compatibility tests (local emulator)"
	cd $(JAVA_DIR) && mvn test -q

test-node-compat-local:
	@echo "==> Node.js SDK compatibility tests (local emulator)"
	@cd $(NODE_DIR) && \
	npm install --silent && \
	npm test

test-servicebus-compat:
	@echo "==> Service Bus Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -Dtest=ServiceBusCompatibilityTest -q

# ── Blob compatibility subset ─────────────────────────────────────────────────

test-blob-python:
	@echo "==> Blob Storage Python SDK compatibility tests (Docker)"
	$(call COMPAT_SESSION,python,blob-python,,--entrypoint pytest,tests/test_blob.py -q --junit-xml=/results/junit.xml)

test-blob-java:
	@echo "==> Blob Storage Java SDK compatibility tests (Docker)"
	$(call COMPAT_SESSION,java,blob-java,,--entrypoint bash,-c 'mvn test -Dtest=BlobCompatibilityTest; status=$$?; cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; exit $$status')

test-blob-node:
	@echo "==> Blob Storage Node.js SDK compatibility tests (Docker)"
	$(call COMPAT_SESSION,node,blob-node,-e JEST_JUNIT_OUTPUT_DIR=/results -e JEST_JUNIT_OUTPUT_NAME=junit.xml,--entrypoint npm,test -- --runTestsByPath tests/blob.test.ts)

test-apim-java:
	@echo "==> API Management Java compatibility tests (Docker)"
	$(call COMPAT_SESSION,java,apim-java,,--entrypoint bash,-c 'mvn test -Dtest=ApiManagementCompatibilityTest; status=$$?; cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; exit $$status')

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
		$(call RUN_SUITE,python,blob-python,,--entrypoint pytest,tests/test_blob.py -q --junit-xml=/results/junit.xml); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		$(call RUN_SUITE,node,blob-node,-e JEST_JUNIT_OUTPUT_DIR=/results -e JEST_JUNIT_OUTPUT_NAME=junit.xml,--entrypoint npm,test -- --runTestsByPath tests/blob.test.ts); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		$(call RUN_SUITE,java,blob-java,,--entrypoint bash,-c 'mvn test -Dtest=BlobCompatibilityTest; status=$$?; cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; exit $$status'); \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-blob-python-local: require-emulator
	@echo "==> Blob Storage Python SDK compatibility tests (local emulator)"
	@cd $(PYTHON_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv; fi && \
	./venv/bin/pip install -q -r requirements.txt && \
	FLOCI_AZ_ENDPOINT=http://127.0.0.1:$(PORT) ./venv/bin/pytest -q tests/test_blob.py

test-blob-java-local: require-emulator
	@echo "==> Blob Storage Java SDK compatibility tests (local emulator)"
	cd $(JAVA_DIR) && FLOCI_AZ_ENDPOINT=http://127.0.0.1:$(PORT) mvn test -Dtest=BlobCompatibilityTest

test-blob-node-local: require-emulator
	@echo "==> Blob Storage Node.js SDK compatibility tests (local emulator)"
	@cd $(NODE_DIR) && \
	npm install --silent && \
	FLOCI_AZ_ENDPOINT=http://127.0.0.1:$(PORT) npm test -- --runTestsByPath tests/blob.test.ts

test-blob-local:
	@echo "==> Blob Storage SDK compatibility tests (local emulator)"
	$(MAKE) run
	$(MAKE) test-blob-python-local; \
	EXIT=$$?; if [ $$EXIT -eq 0 ]; then $(MAKE) test-blob-node-local; EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) test-blob-java-local; EXIT=$$?; fi; \
	$(MAKE) -C $(CURDIR) stop; exit $$EXIT

# ── Cosmos / SQL compatibility ────────────────────────────────────────────────

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

test-eventgrid:
	@echo "==> Azure Event Grid compatibility test (Java SDK, no Docker)"
	$(MAKE) run
	cd $(JAVA_DIR) && mvn test -Dtest=EventGridCompatibilityTest; \
	EXIT=$$?; $(MAKE) -C $(CURDIR) stop; exit $$EXIT

# ── IaC compatibility ─────────────────────────────────────────────────────────

test-terraform-compat:
	@echo "==> Terraform IaC compatibility tests (Docker)"
	$(call COMPAT_SESSION,terraform,terraform,,,)

test-opentofu-compat:
	@echo "==> OpenTofu IaC compatibility tests (Docker)"
	$(call COMPAT_SESSION,opentofu,opentofu,,,)

test-iac-compat:
	$(MAKE) test-terraform-compat
	$(MAKE) test-opentofu-compat

test-azcli:
	@echo "==> Azure CLI compatibility tests (Docker)"
	$(call COMPAT_SESSION,azcli,azcli,,,)

# ── Full compatibility suite ──────────────────────────────────────────────────

compat-docker:
	$(MAKE) compat-build
	$(MAKE) compat-run
	@mkdir -p $(COMPAT_RESULTS)/python $(COMPAT_RESULTS)/node $(COMPAT_RESULTS)/java $(COMPAT_RESULTS)/terraform $(COMPAT_RESULTS)/opentofu $(COMPAT_RESULTS)/azcli
	@EXIT=0; \
	$(MAKE) compat-python-image || EXIT=$$?; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-node-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-java-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-terraform-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-opentofu-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then $(MAKE) compat-azcli-image || EXIT=$$?; fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Python SDK tests"; \
		$(call RUN_SUITE,python,python,$(SUITE_ENV_PYTHON),,); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Node.js SDK tests"; \
		$(call RUN_SUITE,node,node,$(SUITE_ENV_NODE),,); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Java SDK tests"; \
		$(call RUN_SUITE,java,java,$(SUITE_ENV_JAVA) -v /var/run/docker.sock:/var/run/docker.sock,,); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Terraform IaC tests"; \
		$(call RUN_SUITE,terraform,terraform,,,); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> OpenTofu IaC tests"; \
		$(call RUN_SUITE,opentofu,opentofu,,,); \
		EXIT=$$?; \
	fi; \
	if [ $$EXIT -eq 0 ]; then \
		echo "==> Azure CLI tests"; \
		$(call RUN_SUITE,azcli,azcli,,,); \
		EXIT=$$?; \
	fi; \
	$(MAKE) -C $(CURDIR) compat-stop; exit $$EXIT

test-compat:
	$(MAKE) compat-docker

test: build
	$(MVN) test
	$(MAKE) compat-docker

# ── Cleanup ───────────────────────────────────────────────────────────────────

clean:
	$(MVN) clean
	rm -rf $(PYTHON_DIR)/venv $(PYTHON_DIR)/tests/__pycache__
	rm -rf $(NODE_DIR)/node_modules $(NODE_DIR)/dist
	rm -rf $(COMPAT_RESULTS)
	rm -f emulator.log $(PID_FILE)
