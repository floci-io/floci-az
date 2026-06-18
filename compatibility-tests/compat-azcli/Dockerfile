FROM mcr.microsoft.com/azure-cli:latest

# Azure Linux (Mariner) base — bash/curl/jq/openssl ship preinstalled; add git + socat.
RUN tdnf install -y git socat ca-certificates && tdnf clean all

WORKDIR /app

RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY . .

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
ENV BATS_LIB_PATH=/opt
ENV AZURE_SUBSCRIPTION_ID=00000000-0000-0000-0000-000000000001
ENV AZURE_TENANT_ID=00000000-0000-0000-0000-000000000002
ENV AZURE_CLIENT_ID=00000000-0000-0000-0000-000000000003
ENV AZURE_CLIENT_SECRET=fake-secret

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results

ENTRYPOINT ["./run-bats-in-container.sh"]
