package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.deployers.impl.FileConfigurationParser;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.settings.impl.HierarchicalObjectRepository;
import org.apache.activemq.artemis.utils.XMLUtil;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceBusConfigGeneratorTest {

    @Test
    void advertisesMssbcbsOnBothAmqpAcceptors() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.maxDeliveryCount()).thenReturn(10);

        String brokerXml = new ServiceBusConfigGenerator(config).generate("default");

        assertEquals(2, brokerXml.split("saslMechanisms=MSSBCBS,ANONYMOUS,PLAIN", -1).length - 1);
        assertEquals(2, brokerXml.split("anycastPrefix=/", -1).length - 1);
        assertTrue(brokerXml.contains(
                "class-name=\"io.floci.az.artemis.ServiceBusDuplicateDetectionPlugin\""));
        assertTrue(brokerXml.contains(
                "class-name=\"io.floci.az.artemis.ServiceBusExpiryPlugin\""));
        assertTrue(brokerXml.contains(
                "class-name=\"io.floci.az.artemis.ServiceBusManagementPlugin\""));
        assertTrue(brokerXml.contains("<enable-ingress-timestamp>true</enable-ingress-timestamp>"));
    }

    @Test
    void routesAllCbsRequestsExclusivelyToTheResponder() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.maxDeliveryCount()).thenReturn(10);

        String brokerXml = new ServiceBusConfigGenerator(config).generate("default");

        assertTrue(brokerXml.contains("<address>$cbs</address>"));
        assertTrue(brokerXml.contains("<forwarding-address>$cbs-intercept</forwarding-address>"));
        assertTrue(brokerXml.contains("<filter string=\"operation IS NOT NULL\"/>"));
        assertTrue(brokerXml.contains("<exclusive>true</exclusive>"));
        assertTrue(brokerXml.contains("<address name=\"$cbs\"><multicast/></address>"));
        assertTrue(brokerXml.contains("<queue name=\"$cbs-intercept\"/>"));
    }

    /**
     * A message gets its enqueued time from the ingress timestamp, so every entity keeps it. A CBS
     * reply must not carry it: Azure sends no annotation on a CBS reply. Checked with Artemis's own
     * parser and address-setting lookup, which merges a specific match over "#".
     */
    @Test
    void stampsTheIngressTimeOnMessagesButNotOnTheCbsNode() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.maxDeliveryCount()).thenReturn(10);
        String brokerXml = new ServiceBusConfigGenerator(config).generate("default");

        Element core = (Element) XMLUtil.streamToElement(
                new ByteArrayInputStream(brokerXml.getBytes(StandardCharsets.UTF_8))).getElementsByTagName("core").item(0);
        // The parser loads each broker plugin, whose classes are built into the broker's jar alone.
        core.removeChild(core.getElementsByTagName("broker-plugins").item(0));
        Configuration parsed = new ConfigurationImpl();
        new FileConfigurationParser().parseMainConfig(core, parsed);
        HierarchicalObjectRepository<AddressSettings> settings =
                new HierarchicalObjectRepository<>(parsed.getWildcardConfiguration());
        parsed.getAddressSettings().forEach(settings::addMatch);

        for (String address : List.of("orders", "orders.v1", "topic/Subscriptions/audit", "DLQ")) {
            assertTrue(settings.getMatch(address).isEnableIngressTimestamp(), address);
        }
        for (String address : List.of("$cbs", "$cbs-intercept")) {
            assertFalse(settings.getMatch(address).isEnableIngressTimestamp(), address);
            assertEquals(10, settings.getMatch(address).getMaxDeliveryAttempts(), address);
            assertFalse(settings.getMatch(address).isAutoCreateAddresses(), address);
        }
    }

    @Test
    void packagesMssbcbsFactoryForTheArtemisSidecar() throws Exception {
        URL extensionJar;
        try (InputStream stream = getClass().getResourceAsStream(
                ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE)) {
            assertNotNull(stream, "Artemis extension JAR must be embedded in the application");
            Path jar = Files.createTempFile("floci-az-servicebus-artemis-", ".jar");
            Files.copy(stream, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            extensionJar = jar.toUri().toURL();
        }

        try (URLClassLoader loader = new URLClassLoader(new URL[]{extensionJar}, getClass().getClassLoader())) {
            Object factory = ServiceLoader.load(
                            org.apache.activemq.artemis.protocol.amqp.sasl.ServerSASLFactory.class, loader)
                    .stream()
                    .filter(provider -> provider.get().getMechanism().equals("MSSBCBS"))
                    .findFirst()
                    .orElseThrow()
                    .get();

            assertTrue(factory instanceof org.apache.activemq.artemis.protocol.amqp.sasl.ServerSASLFactory);
        }
    }

    @Test
    void packagesServiceBusPluginsForTheArtemisSidecar() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE)) {
            assertNotNull(stream, "Artemis extension JAR must be embedded in the application");
            try (JarInputStream jar = new JarInputStream(stream)) {
                Set<String> expectedClasses = Set.of(
                        "io/floci/az/artemis/ServiceBusExpiryPlugin.class",
                        "io/floci/az/artemis/ServiceBusExpiryPluginMBean.class",
                        "io/floci/az/artemis/ServiceBusManagementPlugin.class");
                Set<String> packagedClasses = new HashSet<>();
                for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                    if (expectedClasses.contains(entry.getName())) {
                        packagedClasses.add(entry.getName());
                    }
                }
                assertEquals(expectedClasses, packagedClasses);
            }
        }
    }

    @Test
    void packagesProtonPatchForAzureCbsTransfers() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                ServiceBusNamespaceManager.PROTON_PATCH_RESOURCE)) {
            assertNotNull(stream, "Patched proton-j JAR must be embedded in the application");
            try (JarInputStream jar = new JarInputStream(stream)) {
                boolean containsTransportPatch = false;
                boolean containsReceiverPatch = false;
                for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                    if (entry.getName().equals(
                            "org/apache/qpid/proton/engine/impl/TransportLink.class")) {
                        containsTransportPatch = true;
                    } else if (entry.getName().equals(
                            "org/apache/qpid/proton/engine/impl/ReceiverImpl.class")) {
                        containsReceiverPatch = true;
                    }
                }
                assertTrue(containsTransportPatch);
                assertTrue(containsReceiverPatch);
            }
        }
    }

    @Test
    void packagesArtemisPatchesForAzureSdkSemantics() throws Exception {
        Set<String> expectedClasses = Set.of(
                "org/apache/activemq/artemis/protocol/amqp/broker/AMQPMessage.class",
                "org/apache/activemq/artemis/protocol/amqp/broker/ServiceBusBatchSupport.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/AmqpTransferTagGenerator.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/DefaultSenderController.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/ProtonServerReceiverContext.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/ServiceBusDeadLetterSupport.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/ProtonServerSenderContext.class");
        try (InputStream stream = getClass().getResourceAsStream(
                ServiceBusNamespaceManager.ARTEMIS_AMQP_PATCH_RESOURCE)) {
            assertNotNull(stream, "Patched Artemis AMQP JAR must be embedded in the application");
            try (JarInputStream jar = new JarInputStream(stream)) {
                Set<String> packagedClasses = new HashSet<>();
                for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                    if (expectedClasses.contains(entry.getName())) {
                        packagedClasses.add(entry.getName());
                    }
                }
                assertEquals(expectedClasses, packagedClasses);
            }
        }
    }
}
