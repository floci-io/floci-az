package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.eventhub.EventHubNamespaceManager.NamespaceState;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When floci-az runs in a container the Artemis broker is a sibling container, not on localhost,
 * so the health probe and the connection endpoint must use the endpoint resolved for the sidecar.
 */
class EventHubHandlerEndpointTest {

    /** Never resolves (RFC 6761), so a probe that reaches a listener did not go through it. */
    private static final String UNRESOLVABLE_HOST = "sidecar.invalid";

    private ServerSocket broker;
    private EmulatorConfig config;
    private EventHubNamespaceManager namespaceManager;

    @BeforeEach
    void setUp() throws IOException {
        broker = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eventHub().defaultNamespace()).thenReturn("emulatorNs1");
        namespaceManager = mock(EventHubNamespaceManager.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        broker.close();
    }

    @Test
    void healthProbesTheBrokerWhereItWasResolved() {
        // The configured ports point at nothing; only the resolved broker endpoint listens.
        closedConfiguredPorts();
        brokerAt("127.0.0.1", broker.getLocalPort());

        Response response = handle("GET", "health");

        assertEquals(200, response.getStatus());
        assertThat(response.getEntity().toString(), containsString("\"status\":\"up\""));
    }

    @Test
    void healthDoesNotProbeLocalhostWhenTheBrokerIsElsewhere() {
        // Something listens on localhost at the configured port, but the broker is not there.
        when(config.services().eventHub().amqpPort()).thenReturn(broker.getLocalPort());
        when(config.services().eventHub().amqpTlsPort()).thenReturn(broker.getLocalPort());
        brokerAt(UNRESOLVABLE_HOST, 5672);

        assertEquals(503, handle("GET", "health").getStatus());
    }

    @Test
    void healthWithoutABrokerKeepsProbingTheConfiguredLocalPorts() {
        when(config.services().eventHub().amqpPort()).thenReturn(broker.getLocalPort());
        when(config.services().eventHub().amqpTlsPort()).thenReturn(broker.getLocalPort());
        when(namespaceManager.listNamespaces()).thenReturn(Map.of());

        assertEquals(200, handle("GET", "health").getStatus());
    }

    @Test
    void connectionInfoPairsLocalhostWithThePublishedPortsWhenTheBrokerIsResolvedAtAContainerIp() {
        // floci-az in a container reaches the broker at its container IP and container ports; a
        // client on the host reaches it at localhost and the ports Docker published.
        NamespaceState state = new NamespaceState(
                "c1", "172.17.0.6", "localhost", 5672, 5671, 49175, 49176, "", false);
        when(namespaceManager.getNamespace("myns")).thenReturn(Optional.of(state));

        String body = handle("GET", "namespaces/myns/connection").getEntity().toString();

        assertThat(body, containsString("\"amqpEndpoint\":\"amqp://localhost:49175\""));
        assertThat(body, containsString("\"amqpsEndpoint\":\"amqps://localhost:49176\""));
        assertThat(body, containsString("\"amqpPort\":5672,\"amqpsPort\":5671"));
        assertThat(body, containsString("\"amqpHostPort\":49175,\"amqpsHostPort\":49176"));
    }

    @Test
    void namespaceReportsThePublishedPortsBesideTheContainerPorts() {
        NamespaceState state = new NamespaceState(
                "c1", "172.17.0.6", "localhost", 5672, 5671, 49175, 49176, "", false);
        when(namespaceManager.getNamespace("myns")).thenReturn(Optional.of(state));

        String body = handle("GET", "namespaces/myns").getEntity().toString();

        assertThat(body, containsString("\"amqpPort\":5672,\"amqpsPort\":5671"));
        assertThat(body, containsString("\"amqpHostPort\":49175,\"amqpsHostPort\":49176"));
    }

    @Test
    void connectionInfoNamesTheDaemonHostInPublishedMode() {
        NamespaceState state = new NamespaceState("c1", "docker", "docker", 47123, 47124, 47123, 47124, "", false);
        when(namespaceManager.getNamespace("myns")).thenReturn(Optional.of(state));

        String body = handle("GET", "namespaces/myns/connection").getEntity().toString();

        assertThat(body, containsString("\"amqpEndpoint\":\"amqp://docker:47123\""));
        assertThat(body, containsString("\"amqpsEndpoint\":\"amqps://docker:47124\""));
    }

    @Test
    void connectionInfoBracketsAnIpv6DaemonHost() {
        NamespaceState state = new NamespaceState("c1", "::1", "::1", 5672, 5671, 5672, 5671, "", false);
        when(namespaceManager.getNamespace("myns")).thenReturn(Optional.of(state));

        assertThat(handle("GET", "namespaces/myns/connection").getEntity().toString(),
                containsString("\"amqpEndpoint\":\"amqp://[::1]:5672\""));
    }

    private void closedConfiguredPorts() {
        when(config.services().eventHub().amqpPort()).thenReturn(1);
        when(config.services().eventHub().amqpTlsPort()).thenReturn(1);
    }

    private void brokerAt(String host, int port) {
        // The published host ports point at nothing: the probe runs from floci-az, so it must use
        // the ports the broker was resolved at.
        when(namespaceManager.listNamespaces()).thenReturn(Map.of(
                "emulatorNs1", new NamespaceState("c1", host, "localhost", port, port, 1, 1, "", false)));
    }

    private Response handle(String method, String path) {
        EventHubHandler handler = new EventHubHandler(config, namespaceManager, mock(EventHubsKafkaManager.class));
        return handler.handle(new AzureRequest(method, "devstoreaccount1", "eventhub", path,
                null, null, Map.of(), null, false));
    }
}
