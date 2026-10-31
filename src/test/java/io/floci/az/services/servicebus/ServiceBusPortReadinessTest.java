package io.floci.az.services.servicebus;

import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;

class ServiceBusPortReadinessTest {

    @ParameterizedTest
    @MethodSource("unresponsiveEndpoints")
    void unresponsiveEndpointReachesReadinessDeadline(String host, int timeoutSeconds) {
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    int timeoutMillis = invocation.getArgument(1);
                    assertTrue(timeoutMillis > 0, "connect must have a finite deadline");
                    assertTrue(timeoutMillis <= 1_000, "connect must stay within its one-second budget");
                    Thread.sleep(timeoutMillis);
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()))) {
            RuntimeException failure = assertTimeout(Duration.ofSeconds(timeoutSeconds).plusMillis(300), () ->
                    assertThrows(RuntimeException.class, () ->
                            ServiceBusNamespaceManager.waitForPort(
                                    new EndpointInfo(host, 5672), "AMQP", timeoutSeconds)));
            assertTrue(failure.getMessage().contains("within " + timeoutSeconds + "s"));
        }
    }

    static Stream<Arguments> unresponsiveEndpoints() {
        return Stream.of(Arguments.of(null, 1), Arguments.of("192.0.2.1", 1),
                Arguments.of("192.0.2.1", 2));
    }

    @Test
    void quickFailureRetriesWithoutSleepingPastRemainingBudget() {
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    if (context.getCount() > 1) {
                        Thread.sleep(400);
                    }
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()))) {
            RuntimeException failure = assertTimeout(Duration.ofMillis(1_300), () ->
                    assertThrows(RuntimeException.class, () ->
                            ServiceBusNamespaceManager.waitForPort(
                                    new EndpointInfo("192.0.2.1", 5672), "AMQP", 1)));
            assertTrue(sockets.constructed().size() >= 2, "an immediate failure must retry within the deadline");
            assertTrue(failure.getMessage().contains("within 1s"));
        }
    }
}
