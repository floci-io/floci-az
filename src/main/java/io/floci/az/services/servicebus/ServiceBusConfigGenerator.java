package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AmqpManagementReply;
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
    /** The CBS node: the address clients send requests to and receive replies on. */
    private static final String CBS_ADDRESS = "$cbs";
    /** The queue the CBS responder reads the requests from. */
    private static final String CBS_INTERCEPT_ADDRESS = "$cbs-intercept";

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
                    .raw("tcp://0.0.0.0:" + AMQP_INTERNAL_PORT
                        + "?protocols=AMQP;anycastPrefix=/"
                        + ";saslMechanisms=MSSBCBS,ANONYMOUS,PLAIN")
                  .end("acceptor")
                  .startAttr("acceptor", "name", "amqps")
                    .raw("tcp://0.0.0.0:" + AMQPS_INTERNAL_PORT + "?protocols=AMQP"
                        + ";anycastPrefix=/"
                        + ";sslEnabled=true"
                        + ";keyStorePath=/var/lib/artemis-instance/etc-override/artemis.p12"
                        + ";keyStorePassword=" + ArtemisTlsGenerator.KEYSTORE_PASSWORD
                        + ";keyStoreType=PKCS12"
                        + ";needClientAuth=false"
                        + ";saslMechanisms=MSSBCBS,ANONYMOUS,PLAIN")
                  .end("acceptor")
                .end("acceptors")
                .start("broker-plugins")
                  .startAttr("broker-plugin", "class-name",
                          "io.floci.az.artemis.ServiceBusDuplicateDetectionPlugin")
                  .end("broker-plugin")
                  .startAttr("broker-plugin", "class-name",
                          "io.floci.az.artemis.ServiceBusExpiryPlugin")
                  .end("broker-plugin")
                  .startAttr("broker-plugin", "class-name",
                          "io.floci.az.artemis.ServiceBusManagementPlugin")
                  .end("broker-plugin")
                .end("broker-plugins")
                .start("address-settings")
                  .startAttr("address-setting", "match", "#")
                    .elem("max-delivery-attempts", maxDelivery)
                    .elem("dead-letter-address", "DLQ")
                    .elem("auto-create-queues", false)
                    .elem("auto-create-addresses", false)
                    .elem("enable-ingress-timestamp", true)
                  .end("address-setting")
                  // A message gets its enqueued time from the ingress timestamp, which Artemis
                  // adds as an annotation when a client or a responder sends it. A CBS reply is
                  // sent by the responder, and Azure sends no annotation on it, so the CBS node
                  // has no ingress timestamp. Every other setting comes from "#". The replies on
                  // an entity management node are built by the broker and routed without a send,
                  // so they never get one.
                  .startAttr("address-setting", "match", CBS_ADDRESS)
                    .elem("enable-ingress-timestamp", false)
                  .end("address-setting")
                  .startAttr("address-setting", "match", CBS_INTERCEPT_ADDRESS)
                    .elem("enable-ingress-timestamp", false)
                  .end("address-setting")
                .end("address-settings")
                .start("diverts")
                  .startAttr("divert", "name", "cbs-request-intercept")
                    .elem("address", CBS_ADDRESS)
                    .elem("forwarding-address", CBS_INTERCEPT_ADDRESS)
                    .selfClose("filter", "string", AmqpManagementReply.REQUEST_FILTER)
                    .elem("exclusive", true)
                  .end("divert")
                .end("diverts")
                .start("addresses")
                  .startAttr("address", "name", "DLQ")
                    .start("anycast")
                      .selfClose("queue", "name", "DLQ")
                    .end("anycast")
                  .end("address")
                  .startAttr("address", "name", CBS_ADDRESS)
                    .selfClose("multicast")
                  .end("address")
                  .startAttr("address", "name", CBS_INTERCEPT_ADDRESS)
                    .start("anycast")
                      .selfClose("queue", "name", CBS_INTERCEPT_ADDRESS)
                    .end("anycast")
                  .end("address")
                .end("addresses")
              .end("core")
            .end("configuration");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" + xml.build();
    }
}
