FROM hashicorp/terraform:1.10 AS terraform

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl git jq ca-certificates socat \
    && rm -rf /var/lib/apt/lists/*

COPY --from=terraform /bin/terraform /usr/local/bin/terraform

WORKDIR /app

RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY . .

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
ENV BATS_LIB_PATH=/opt
ENV TF_VAR_subscription_id=00000000-0000-0000-0000-000000000001
ENV TF_VAR_tenant_id=00000000-0000-0000-0000-000000000002

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results

ENTRYPOINT ["./run-bats-in-container.sh"]
