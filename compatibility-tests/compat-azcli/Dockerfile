FROM mcr.microsoft.com/azure-cli:latest

# Azure Linux (Mariner) base — bash/curl/jq/openssl ship preinstalled; add git + socat.
RUN tdnf install -y git socat ca-certificates && tdnf clean all

WORKDIR /app

RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

# The azure-iot extension, for az iot hub device-identity. Pinned by version and checksum, so a new release
# cannot change what the suite tests.
ARG AZURE_IOT_VERSION=1.0.0
ARG AZURE_IOT_SHA256=8f2d70d30c07f18e8ef5857d6bedb948efbab4dac845a719d5e036652721931c
RUN curl -fsSL -o /tmp/azure_iot-${AZURE_IOT_VERSION}-py3-none-any.whl \
        "https://github.com/Azure/azure-iot-cli-extension/releases/download/v${AZURE_IOT_VERSION}/azure_iot-${AZURE_IOT_VERSION}-py3-none-any.whl" \
    && echo "${AZURE_IOT_SHA256}  /tmp/azure_iot-${AZURE_IOT_VERSION}-py3-none-any.whl" | sha256sum -c - \
    && az extension add --source /tmp/azure_iot-${AZURE_IOT_VERSION}-py3-none-any.whl --yes \
    && rm /tmp/azure_iot-${AZURE_IOT_VERSION}-py3-none-any.whl

COPY . .

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
ENV BATS_LIB_PATH=/opt
ENV AZURE_SUBSCRIPTION_ID=00000000-0000-0000-0000-000000000001
ENV AZURE_TENANT_ID=00000000-0000-0000-0000-000000000002
ENV AZURE_CLIENT_ID=11111111-1111-1111-1111-111111111111
ENV AZURE_CLIENT_SECRET=floci-az-dev-secret

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results

ENTRYPOINT ["./run-bats-in-container.sh"]
