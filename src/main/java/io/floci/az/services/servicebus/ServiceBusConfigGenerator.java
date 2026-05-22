package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.XmlBuilder;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Generates Artemis broker.xml for a Service Bus namespace.
 *
 * Unlike Event Hubs, entity topology is NOT pre-configured — queues and topics are
 * provisioned dynamically via Jolokia when the management API creates them.
 * Only the AMQP acceptors and the pre-declared DLQ address are in the static config.
 */
@ApplicationScoped
public class ServiceBusConfigGenerator {

    static final int AMQP_INTERNAL_PORT = 5672;
    static final int AMQPS_INTERNAL_PORT = 5671;

    private final EmulatorConfig config;

    @Inject
    public ServiceBusConfigGenerator(EmulatorConfig config) {
        this.config = config;
    }

    public String generate(String namespaceName) {
        int maxDelivery = config.services().serviceBus().maxDeliveryCount();
        XmlBuilder xml = new XmlBuilder()
            .start("configuration", "urn:activemq")
              .start("core", "urn:activemq:core")
                .elem("name", "floci-az-servicebus-" + namespaceName)
                .elem("security-enabled", false)
                .start("acceptors")
                  .startAttr("acceptor", "name", "amqp")
                    .raw("tcp://0.0.0.0:" + AMQP_INTERNAL_PORT + "?protocols=AMQP;saslMechanisms=ANONYMOUS,PLAIN;maxMessageSize=1048576")
                  .end("acceptor")
                  .startAttr("acceptor", "name", "amqps")
                    .raw("tcp://0.0.0.0:" + AMQPS_INTERNAL_PORT + "?protocols=AMQP"
                        + ";sslEnabled=true"
                        + ";keyStorePath=/var/lib/artemis-instance/etc-override/artemis.p12"
                        + ";keyStorePassword=" + ArtemisTlsGenerator.KEYSTORE_PASSWORD
                        + ";keyStoreType=PKCS12"
                        + ";needClientAuth=false"
                        + ";saslMechanisms=ANONYMOUS,PLAIN"
                        + ";maxMessageSize=1048576")
                  .end("acceptor")
                .end("acceptors")
                .start("address-settings")
                  .startAttr("address-setting", "match", "#")
                    .elem("max-delivery-attempts", maxDelivery)
                    .elem("dead-letter-address", "DLQ")
                    .elem("auto-create-queues", false)
                    .elem("auto-create-addresses", false)
                  .end("address-setting")
                .end("address-settings")
                .start("diverts")
                  .startAttr("divert", "name", "cbs-put-token-intercept")
                    .elem("address", "$cbs")
                    .elem("forwarding-address", "$cbs-intercept")
                    .selfClose("filter", "string", "operation = 'put-token'")
                    .elem("exclusive", true)
                  .end("divert")
                .end("diverts")
                .start("addresses")
                  .startAttr("address", "name", "DLQ")
                    .start("anycast")
                      .selfClose("queue", "name", "DLQ")
                    .end("anycast")
                  .end("address")
                  .startAttr("address", "name", "$cbs")
                    .start("anycast")
                      .selfClose("queue", "name", "$cbs")
                    .end("anycast")
                  .end("address")
                  .startAttr("address", "name", "$cbs-intercept")
                    .start("anycast")
                      .selfClose("queue", "name", "$cbs-intercept")
                    .end("anycast")
                  .end("address")
                  .startAttr("address", "name", "cbs-client-reply-to")
                    .start("anycast")
                      .selfClose("queue", "name", "cbs-client-reply-to")
                    .end("anycast")
                  .end("address")
                .end("addresses")
              .end("core")
            .end("configuration");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" + xml.build();
    }
}
