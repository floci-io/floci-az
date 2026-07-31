package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(brokerXml.contains("<filter string=\"operation = &apos;put-token&apos;\"/>"));
        assertTrue(brokerXml.contains("<exclusive>true</exclusive>"));
        assertTrue(brokerXml.contains("<queue name=\"$cbs-intercept\"/>"));
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
    void packagesMessageExpiryPluginForTheArtemisSidecar() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE)) {
            assertNotNull(stream, "Artemis extension JAR must be embedded in the application");
            try (JarInputStream jar = new JarInputStream(stream)) {
                Set<String> expectedClasses = Set.of(
                        "io/floci/az/artemis/ServiceBusExpiryPlugin.class",
                        "io/floci/az/artemis/ServiceBusExpiryPluginMBean.class");
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
                "org/apache/activemq/artemis/protocol/amqp/proton/AmqpTransferTagGenerator.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/DefaultSenderController.class",
                "org/apache/activemq/artemis/protocol/amqp/proton/ProtonServerReceiverContext.class",
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
