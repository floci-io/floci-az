FROM maven:3.9-eclipse-temurin-21
WORKDIR /app

# Docker CLI is needed so the invocation tests can confirm Docker is reachable
# before attempting to spawn Azure Functions containers via floci-az.
RUN apt-get update \
    && apt-get install -y --no-install-recommends docker.io \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn test-compile -q

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577

RUN mkdir -p /results
ENTRYPOINT ["bash", "-c", \
  "status=0; mvn test -q -Dorg.slf4j.simpleLogger.defaultLogLevel=warn || status=$?; \
   cp target/surefire-reports/TEST-*.xml /results/ 2>/dev/null || true; \
   exit $status"]
