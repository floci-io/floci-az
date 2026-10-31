package io.floci.az.services.servicebus;

import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;

class ServiceBusPortReadinessTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "192.0.2.1")
    void unresponsiveEndpointReachesReadinessDeadline(String host) {
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    int timeoutMillis = invocation.getArgument(1);
                    assertTrue(timeoutMillis > 0, "connect must have a finite deadline");
                    Thread.sleep(timeoutMillis);
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()))) {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    ServiceBusNamespaceManager.waitForPort(new EndpointInfo(host, 5672), "AMQP", 1));
            assertTrue(failure.getMessage().contains("within 1s"));
        }
    }
}
