FROM hashicorp/terraform:1.10 AS terraform

# glibc: azd's Bicep download path rejects x64 musl, so never Alpine (local/plan/azd.md).
FROM debian:bookworm-slim

ARG AZD_VERSION=1.36.0
ARG BICEP_VERSION=0.48.1

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends ca-certificates curl git jq libicu72 socat tar \
    && rm -rf /var/lib/apt/lists/*

COPY --from=terraform /bin/terraform /usr/local/bin/terraform

RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://azuresdkartifacts.z5.web.core.windows.net/azd/standalone/release/${AZD_VERSION}/azd-linux-${arch}.tar.gz" -o /tmp/azd.tar.gz \
    && mkdir -p /opt/azd && tar -xzf /tmp/azd.tar.gz -C /opt/azd \
    && ln -s "/opt/azd/azd-linux-${arch}" /usr/local/bin/azd \
    && rm /tmp/azd.tar.gz \
    && bicep_arch="$([ "$arch" = amd64 ] && echo x64 || echo "$arch")" \
    && curl -fsSL "https://downloads.bicep.azure.com/v${BICEP_VERSION}/bicep-linux-${bicep_arch}" -o /usr/local/bin/bicep \
    && chmod +x /usr/local/bin/bicep

RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

WORKDIR /app
COPY . .

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
ENV BATS_LIB_PATH=/opt
ENV AZURE_SUBSCRIPTION_ID=00000000-0000-0000-0000-000000000001
ENV AZURE_TENANT_ID=00000000-0000-0000-0000-000000000002
ENV AZURE_CLIENT_ID=11111111-1111-1111-1111-111111111111
ENV AZURE_CLIENT_SECRET=floci-az-dev-secret
# azd calls these on its own: telemetry and the update check would leave the emulator's network.
ENV AZURE_DEV_COLLECT_TELEMETRY=no
ENV AZD_SKIP_UPDATE_CHECK=true
ENV AZD_BICEP_TOOL_PATH=/usr/local/bin/bicep

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results

ENTRYPOINT ["./run-bats-in-container.sh"]
