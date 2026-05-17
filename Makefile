.PHONY: build run stop test test-python test-java-compat test-node-compat test-appconfig test-keyvault test-eventhub compat-docker clean

MVN            = ./mvnw
PORT           = 4577
PID_FILE       = emulator.pid
PYTHON_DIR     = compatibility-tests/sdk-test-python
JAVA_DIR       = compatibility-tests/sdk-test-java
NODE_DIR       = compatibility-tests/sdk-test-node
APPCONFIG_DIR  = compatibility-tests/sdk-test-appconfig
KEYVAULT_DIR   = compatibility-tests/sdk-test-keyvault
EVENTHUB_DIR   = compatibility-tests/sdk-test-eventhub

build:
	$(MVN) compile

run:
	$(MVN) quarkus:dev -Dno-color > emulator.log 2>&1 & echo $$! > $(PID_FILE)
	@echo "Waiting for emulator to start on port $(PORT)..."
	@until curl -s http://localhost:$(PORT)/health > /dev/null; do sleep 1; done
	@echo "Emulator is up!"

stop:
	@if [ -f $(PID_FILE) ]; then \
		kill $$(cat $(PID_FILE)) && rm $(PID_FILE); \
		echo "Emulator stopped."; \
	else \
		echo "No emulator running."; \
	fi

test-python:
	@echo "==> Python SDK compatibility tests"
	@cd $(PYTHON_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv && ./venv/bin/pip install -q -r requirements.txt; fi && \
	./venv/bin/pytest tests/ -v

test-java-compat:
	@echo "==> Java SDK compatibility tests"
	cd $(JAVA_DIR) && mvn test -q

test-node-compat:
	@echo "==> Node SDK compatibility tests"
	@cd $(NODE_DIR) && \
	if [ ! -d node_modules ]; then npm install --silent; fi && \
	npm test

test-appconfig:
	@echo "==> App Configuration SDK compatibility tests"
	@cd $(APPCONFIG_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv && ./venv/bin/pip install -q -r requirements.txt; fi && \
	./venv/bin/pytest tests/ -v

test-keyvault:
	@echo "==> Key Vault SDK compatibility tests"
	@cd $(KEYVAULT_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv && ./venv/bin/pip install -q -r requirements.txt; fi && \
	./venv/bin/pytest tests/ -v

test-eventhub:
	@echo "==> Event Hubs SDK compatibility tests"
	@cd $(EVENTHUB_DIR) && \
	if [ ! -d venv ]; then python3 -m venv venv && ./venv/bin/pip install -q -r requirements.txt; fi && \
	./venv/bin/pytest tests/ -v

# Run all compatibility tests in Docker containers against the running floci-az.
# Requires: docker compose up -d
compat-docker:
	@echo "==> Building test images..."
	@docker build -q -t floci-az-compat-node      -f $(NODE_DIR)/Dockerfile      $(NODE_DIR)/
	@docker build -q -t floci-az-compat-python    -f $(PYTHON_DIR)/Dockerfile    $(PYTHON_DIR)/
	@docker build -q -t floci-az-compat-java      -f $(JAVA_DIR)/Dockerfile      $(JAVA_DIR)/
	@docker build -q -t floci-az-compat-appconfig -f $(APPCONFIG_DIR)/Dockerfile $(APPCONFIG_DIR)/
	@docker build -q -t floci-az-compat-keyvault  -f $(KEYVAULT_DIR)/Dockerfile  $(KEYVAULT_DIR)/
	@docker build -q --platform linux/amd64 -t floci-az-compat-eventhub -f $(EVENTHUB_DIR)/Dockerfile $(EVENTHUB_DIR)/
	@echo "==> Node.js SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
		-e EVENTHUB_AMQP_PORT=5672 \
		floci-az-compat-node
	@echo "==> Python SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		floci-az-compat-python
	@echo "==> Java SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		-v /var/run/docker.sock:/var/run/docker.sock \
		floci-az-compat-java
	@echo "==> App Configuration SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		floci-az-compat-appconfig
	@echo "==> Key Vault SDK tests"
	docker run --rm --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		floci-az-compat-keyvault
	@echo "==> Event Hubs SDK tests"
	docker run --rm --platform linux/amd64 --network floci_az_default \
		-e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
		-e EVENTHUB_HOST=floci-az-artemis-emulatorNs1 \
		-e EVENTHUB_AMQPS_PORT=5671 \
		floci-az-compat-eventhub

test: build
	$(MVN) test
	$(MAKE) run
	$(MAKE) test-python
	$(MAKE) test-java-compat
	$(MAKE) test-node-compat
	$(MAKE) test-appconfig
	$(MAKE) test-keyvault
	$(MAKE) test-eventhub
	$(MAKE) stop

clean:
	$(MVN) clean
	rm -rf $(PYTHON_DIR)/venv $(PYTHON_DIR)/tests/__pycache__
	rm -rf $(NODE_DIR)/node_modules $(NODE_DIR)/dist
	rm -rf $(APPCONFIG_DIR)/venv $(APPCONFIG_DIR)/tests/__pycache__
	rm -rf $(KEYVAULT_DIR)/venv $(KEYVAULT_DIR)/tests/__pycache__
	rm -rf $(EVENTHUB_DIR)/venv $(EVENTHUB_DIR)/tests/__pycache__
	rm -f emulator.log $(PID_FILE)
