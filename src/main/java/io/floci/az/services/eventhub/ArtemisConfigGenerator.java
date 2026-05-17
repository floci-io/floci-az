package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.XmlBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the Artemis broker.xml for an Event Hubs namespace.
 *
 * The broker.xml statically configures both:
 * - MULTICAST addresses for the rhea-promise (Node.js) SDK (path-only addressing)
 * - ANYCAST addresses, durable queues, and exclusive diverts for the uamqp (Python) SDK
 *   (full AMQP URI addressing: {@code amqp://hostname/namespace/entity})
 *
 * Pre-configuring topology in broker.xml avoids any dependency on the Jolokia management API,
 * which can take several minutes to start and would otherwise block readiness.
 */
@ApplicationScoped
public class ArtemisConfigGenerator {

    private static final String DEFAULT_CONSUMER_GROUP = "$Default";

    private final EmulatorConfig config;

    @Inject
    public ArtemisConfigGenerator(EmulatorConfig config) {
        this.config = config;
    }

    /** Returns broker.xml for the given namespace and entities (used for dynamic namespace creation). */
    public String generate(String namespace, Map<String, List<String>> entities) {
        String containerName = "floci-az-artemis-" + namespace;
        List<String> hostnames = List.of("localhost", containerName);

        XmlBuilder addresses = new XmlBuilder();
        XmlBuilder diverts = new XmlBuilder();

        for (Map.Entry<String, List<String>> entry : entities.entrySet()) {
            String entityName = entry.getKey();
            List<String> cgs = entry.getValue();

            appendMulticastAddress(addresses, namespace + "/" + entityName, cgs);

            for (String hostname : hostnames) {
                appendAnycastTopology(addresses, diverts, hostname, namespace, entityName, cgs);
            }
        }
        return buildBrokerXml(addresses, diverts);
    }

    /** Returns broker.xml using the default namespace/entities from config. */
    public String generate() {
        EmulatorConfig.EventHubConfig eh = config.services().eventHub();
        return generate(eh.defaultNamespace(), parseEntities(eh.entities(), eh.consumerGroups()));
    }

    /**
     * Parses "eh1:4,eh2:2" and a consumer groups string into entityName → consumer group list.
     * The partition count in the entities string is accepted but ignored (Artemis handles routing).
     */
    public static Map<String, List<String>> parseEntities(String entitiesStr, String consumerGroupsStr) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> groups = parseConsumerGroups(consumerGroupsStr);
        if (entitiesStr == null || entitiesStr.isBlank()) {
            result.put("eh1", groups);
            return result;
        }
        for (String token : entitiesStr.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            String name = token.split(":")[0].trim();
            result.put(name, groups);
        }
        return result;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildBrokerXml(XmlBuilder addresses, XmlBuilder diverts) {
        XmlBuilder xml = new XmlBuilder()
            .start("configuration", "urn:activemq")
              .start("core", "urn:activemq:core")
                .elem("name", "floci-az-eventhubs")
                .start("acceptors")
                  .startAttr("acceptor", "name", "amqp")
                    .raw("tcp://0.0.0.0:5672?protocols=AMQP")
                  .end("acceptor")
                  .startAttr("acceptor", "name", "amqps")
                    .raw("tcp://0.0.0.0:5671?protocols=AMQP"
                        + ";sslEnabled=true"
                        + ";keyStorePath=/var/lib/artemis-instance/etc-override/artemis.p12"
                        + ";keyStorePassword=" + ArtemisTlsGenerator.KEYSTORE_PASSWORD
                        + ";keyStoreType=PKCS12"
                        + ";needClientAuth=false")
                  .end("acceptor")
                .end("acceptors")
                .elem("security-enabled", false)
                .start("address-settings")
                  .startAttr("address-setting", "match", "#")
                    .elem("auto-create-queues", true)
                    .elem("auto-create-addresses", true)
                    .elem("default-address-routing-type", "MULTICAST")
                    .elem("default-queue-routing-type", "MULTICAST")
                  .end("address-setting")
                .end("address-settings")
                .start("addresses")
                  .raw(addresses.build())
                .end("addresses");

        String divertsXml = diverts.build();
        if (!divertsXml.isEmpty()) {
            xml.start("diverts").raw(divertsXml).end("diverts");
        }

        xml.end("core").end("configuration");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" + xml.build();
    }

    /** MULTICAST address with one queue per consumer group (for rhea-promise / Node.js). */
    private void appendMulticastAddress(XmlBuilder builder, String addr, List<String> consumerGroups) {
        XmlBuilder queues = new XmlBuilder();
        for (String cg : consumerGroups) {
            queues.selfClose("queue", "name", addr + "/" + cg);
        }
        builder
            .startAttr("address", "name", addr)
              .start("multicast")
                .raw(queues.build())
              .end("multicast")
            .end("address");
    }

    /**
     * Appends ANYCAST addresses, durable queues, and exclusive diverts for the Python uamqp SDK,
     * which uses full AMQP URI addressing: {@code amqp://hostname/namespace/entity}.
     */
    private void appendAnycastTopology(XmlBuilder addresses, XmlBuilder diverts,
                                        String hostname, String namespace, String entity,
                                        List<String> consumerGroups) {
        // uamqp lowercases the hostname portion of AMQP URIs, so we must match that
        String entityAddr = "amqp://" + hostname.toLowerCase(java.util.Locale.US) + "/" + namespace + "/" + entity;

        addresses.startAttr("address", "name", entityAddr)
                   .selfClose("anycast")
                 .end("address");

        for (String cg : consumerGroups) {
            String cgAddr = entityAddr + "/" + cg;
            String divertName = (hostname + "-" + entity + "-to-" + cg)
                    .replaceAll("[^A-Za-z0-9_-]", "-");

            addresses.startAttr("address", "name", cgAddr)
                       .start("anycast")
                         .startAttr("queue", "name", cgAddr)
                           .elem("durable", true)
                         .end("queue")
                       .end("anycast")
                     .end("address");

            diverts.startAttr("divert", "name", divertName)
                     .elem("address", entityAddr)
                     .elem("forwarding-address", cgAddr)
                     .elem("exclusive", true)
                   .end("divert");
        }
    }

    private static List<String> parseConsumerGroups(String raw) {
        List<String> groups = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            groups.add(DEFAULT_CONSUMER_GROUP);
            return groups;
        }
        for (String g : raw.split(",")) {
            String trimmed = g.trim();
            if (!trimmed.isEmpty()) {
                groups.add(trimmed);
            }
        }
        if (groups.isEmpty()) {
            groups.add(DEFAULT_CONSUMER_GROUP);
        }
        return groups;
    }
}
