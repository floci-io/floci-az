.PHONY: build run stop test test-python test-java-compat test-node-compat clean

MVN        = ./mvnw
PORT       = 4577
PID_FILE   = emulator.pid
PYTHON_DIR = compatibility-tests/sdk-test-python
JAVA_DIR   = compatibility-tests/sdk-test-java
NODE_DIR   = compatibility-tests/sdk-test-node

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

test: build
	$(MVN) test
	$(MAKE) run
	$(MAKE) test-python
	$(MAKE) test-java-compat
	$(MAKE) test-node-compat
	$(MAKE) stop

clean:
	$(MVN) clean
	rm -rf $(PYTHON_DIR)/venv $(PYTHON_DIR)/tests/__pycache__
	rm -rf $(NODE_DIR)/node_modules $(NODE_DIR)/dist
	rm -f emulator.log $(PID_FILE)
