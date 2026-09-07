# Stage 1: Build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src/ src/
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine

ARG VERSION=latest
ENV FLOCI_AZ_VERSION=${VERSION}
ENV FLOCI_AZ_STORAGE_PERSISTENT_PATH=/app/data
ENV FLOCI_AZ_SERVICES_FUNCTIONS_CODE_PATH=/app/data/functions

# Privilege drop happens in the entrypoint through coreutils chroot, so no gosu
# download. The base image ships GNU coreutils at /usr/sbin/chroot, ahead of
# busybox on PATH, so --userspec and --skip-chdir are available.
RUN set -eux; \
    apk add --no-cache shadow ca-certificates; \
    useradd -r -u 1001 -g root -d /app -s /sbin/nologin floci
WORKDIR /app

RUN mkdir -p /app/data \
    && chown 1001:root /app \
    && chmod "g+rwX" /app \
    && chown 1001:root /app/data

COPY --from=build /build/target/quarkus-app/ quarkus-app/
RUN chown -R 1001:root /app/quarkus-app

COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh

VOLUME /app/data

EXPOSE 4577

HEALTHCHECK --interval=5s --timeout=3s --retries=5 \
    CMD wget -q --spider http://localhost:4577/_floci/health || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/quarkus-app/quarkus-run.jar"]
