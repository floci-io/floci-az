package io.floci.az.services.signalr;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Transient connection ownership and hub fan-out, shared by all application server connections. */
final class SignalRConnections {
    private static final Logger LOG = Logger.getLogger(SignalRConnections.class);
    record Hub(String account, String name) {}
    private final Set<Server> servers = new LinkedHashSet<>();
    private final Map<String, Client> clients = new HashMap<>();
    private int nextServer;

    final class Server {
        final Hub hub;
        final ServerWebSocket socket;
        final String name;
        final SignalRProtocol protocol = new SignalRProtocol();
        final long opened = System.currentTimeMillis();
        boolean ready;
        boolean acceptsClients;

        Server(Hub hub, ServerWebSocket socket) {
            this.hub = hub; this.socket = socket;
            this.name = Objects.toString(socket.headers().get("X-ASRS-Server-Id"), "");
        }

        void send(Object... fields) {
            if (socket.writeQueueFull()) { socket.close((short) 1013, "Application server cannot keep up"); return; }
            if (!socket.isClosed()) { socket.writeBinaryMessage(Buffer.buffer(SignalRProtocol.frame(fields))); }
        }
    }

    private static final class Client {
        final String id;
        final String user;
        final Server server;
        final ServerWebSocket socket;
        final SignalRTokens.Claims claims;
        final String query;
        final Set<String> groups = new HashSet<>();
        String protocol;
        Buffer handshake = Buffer.buffer();
        final long opened = System.currentTimeMillis();

        Client(String id, String user, Server server, ServerWebSocket socket, SignalRTokens.Claims claims, String query) {
            this.id = id; this.user = user; this.server = server; this.socket = socket;
            this.claims = claims; this.query = query;
        }

        void send(byte[] payload) {
            if (socket.isClosed()) { return; }
            if (socket.writeQueueFull()) { socket.close((short) 1013, "Client cannot keep up"); return; }
            if ("messagepack".equals(protocol)) { socket.writeBinaryMessage(Buffer.buffer(payload)); }
            else { socket.writeTextMessage(new String(payload, StandardCharsets.UTF_8)); }
        }
    }

    synchronized void server(Hub hub, ServerWebSocket socket) {
        Server server = new Server(hub, socket);
        servers.add(server);
        socket.binaryMessageHandler(bytes -> {
            synchronized (this) {
                try { server.protocol.accept(bytes.getBytes(), message -> message(server, message)); }
                catch (Exception e) {
                    LOG.debug("Invalid SignalR service message", e);
                    socket.close((short) 1002, "Invalid service message");
                }
            }
        });
        socket.textMessageHandler(text -> socket.close((short) 1003, "Service protocol requires binary frames"));
        socket.closeHandler(ignored -> disconnect(server));
        socket.exceptionHandler(error -> { LOG.debug("SignalR server connection failed", error); disconnect(server); });
    }

    synchronized boolean hasServer(Hub hub) {
        return servers.stream().anyMatch(server -> server.ready && server.acceptsClients && server.hub.equals(hub));
    }

    synchronized void client(Hub hub, String id, SignalRTokens.Claims claims, String query, ServerWebSocket socket) {
        List<Server> available = servers.stream().filter(server -> server.ready && server.acceptsClients && server.hub.equals(hub)).toList();
        String sticky = claims.value("asrs.s.ssticky");
        if (Set.of("Preferred", "Required").contains(sticky)) {
            List<Server> matching = available.stream().filter(server -> server.name.equals(claims.value("asrs.s.sn"))).toList();
            if (!matching.isEmpty() || sticky.equals("Required")) { available = matching; }
        }
        if (available.isEmpty()) { socket.close((short) 1013, "No application server connected"); return; }
        Server server = available.get(Math.floorMod(nextServer++, available.size()));
        Client client = new Client(id, claims.userId(), server, socket, claims, query);
        clients.put(id, client);
        socket.binaryMessageHandler(bytes -> data(client, bytes));
        socket.textMessageHandler(text -> data(client, Buffer.buffer(text)));
        socket.closeHandler(ignored -> disconnect(client));
        socket.exceptionHandler(error -> { LOG.debug("SignalR client connection failed", error); disconnect(client); });
    }

    private synchronized void data(Client client, Buffer bytes) {
        if (!clients.containsKey(client.id)) { return; }
        if (client.protocol == null) {
            client.handshake.appendBuffer(bytes);
            if (client.handshake.length() > 32768) { client.socket.close((short) 1009, "Handshake too large"); return; }
            byte[] handshake = client.handshake.getBytes();
            int end = -1;
            for (int index = 0; index < handshake.length; index++) {
                if (handshake[index] == 30) { end = index; break; }
            }
            if (end == -1) { return; }
            try {
                client.protocol = new JsonObject(new String(handshake, 0, end, StandardCharsets.UTF_8)).getString("protocol");
                if (!Set.of("json", "messagepack").contains(client.protocol)) { throw new IllegalArgumentException("Unsupported hub protocol"); }
            } catch (Exception e) {
                LOG.debug("Invalid SignalR client handshake", e);
                client.socket.close((short) 1002, "Invalid hub handshake");
                return;
            }
            bytes = client.handshake;
            client.handshake = null;
            // SDK 1.33 requires the hub protocol in OpenConnection extension member 3.
            client.server.send(4, client.id, client.claims, Map.of(), client.query, Map.of(3, client.protocol));
        }
        client.server.send(6, client.id, bytes.getBytes(), Map.of());
    }

    private void message(Server server, List<Object> fields) {
        int type = ((Number) fields.getFirst()).intValue();
        if (!server.ready) {
            if (type != 1 || ((Number) fields.get(1)).intValue() != 1) {
                server.send(2, "Only service protocol version 1 is supported");
                server.socket.close((short) 1002, "Unsupported service protocol");
                return;
            }
            server.acceptsClients = ((Number) fields.get(2)).intValue() != 2;
            server.ready = true;
            server.send(2, "", Map.of(), UUID.randomUUID().toString());
            return;
        }
        switch (type) {
            case 3 -> ping(server, fields);
            case 5 -> {
                Client client = clients.get(text(fields, 1));
                if (client != null && client.server.hub.equals(server.hub)) { client.socket.close(); }
            }
            case 6 -> {
                Client client = clients.get(text(fields, 1));
                if (client != null && client.server == server) { client.send((byte[]) fields.get(2)); }
            }
            case 7 -> fanout(server.hub, client -> list(fields, 1).contains(client.id), payloads(fields, 2));
            case 8 -> fanout(server.hub, client -> client.user.equals(text(fields, 1)), payloads(fields, 2));
            case 9 -> fanout(server.hub, client -> list(fields, 1).contains(client.user), payloads(fields, 2));
            case 10 -> fanout(server.hub, client -> !list(fields, 1).contains(client.id), payloads(fields, 2));
            case 11, 12, 18, 19 -> group(server, fields, type);
            case 13 -> fanout(server.hub, client -> client.groups.contains(text(fields, 1))
                    && !list(fields, 2).contains(client.id)
                    && (fields.size() < 6 || !list(fields, 5).contains(client.user)), payloads(fields, 3));
            case 14 -> fanout(server.hub, client -> list(fields, 1).stream().anyMatch(client.groups::contains), payloads(fields, 2));
            default -> {
                LOG.debugv("Unsupported SignalR service message type {0}", type);
                server.socket.close((short) 1003, "Unsupported service message type " + type);
            }
        }
    }

    private void group(Server server, List<Object> fields, int type) {
        Client client = clients.get(text(fields, 1));
        boolean found = client != null && client.server.hub.equals(server.hub);
        if (found) {
            if (type == 11 || type == 18) { client.groups.add(text(fields, 2)); }
            else { client.groups.remove(text(fields, 2)); }
        }
        if (type == 18 || type == 19) { server.send(20, fields.get(3), found ? 1 : 2, "", Map.of()); }
    }

    private void fanout(Hub hub, Predicate<Client> target, Map<?, ?> payloads) {
        for (Client client : clients.values()) {
            if (client.server.hub.equals(hub) && target.test(client) && payloads.get(client.protocol) instanceof byte[] bytes) {
                client.send(bytes);
            }
        }
    }

    private void ping(Server server, List<Object> fields) {
        if (fields.contains("status")) {
            server.send(3, "status", clients.values().stream().anyMatch(client -> client.server == server) ? "1" : "0");
        } else if (fields.contains("servers")) {
            String names = String.join(";", servers.stream().filter(candidate -> candidate.ready && candidate.hub.equals(server.hub))
                    .map(candidate -> candidate.name).distinct().toList());
            server.send(3, "servers", System.currentTimeMillis() + ":" + names);
        } else if (fields.contains("offline")) {
            server.acceptsClients = false;
            clients.values().stream().filter(client -> client.server == server).toList().forEach(client -> client.socket.close());
            server.send(3, "offline", "finack");
        } else if (fields.size() >= 3 && "echo".equals(fields.get(1))) { server.send(3, "echo", fields.get(2)); }
    }

    synchronized void tick() {
        servers.stream().filter(server -> server.ready).forEach(server -> server.send(3));
        long deadline = System.currentTimeMillis() - 15000;
        servers.stream().filter(server -> !server.ready && server.opened < deadline).toList()
                .forEach(server -> server.socket.close((short) 1008, "Handshake timeout"));
        clients.values().stream().filter(client -> client.protocol == null && client.opened < deadline).toList()
                .forEach(client -> client.socket.close((short) 1008, "Handshake timeout"));
    }

    private synchronized void disconnect(Server server) {
        servers.remove(server);
        clients.values().stream().filter(client -> client.server == server).toList().forEach(client -> {
            clients.remove(client.id);
            client.socket.close((short) 1011, "Application server disconnected");
        });
    }

    private synchronized void disconnect(Client client) {
        if (clients.remove(client.id, client) && client.protocol != null) { client.server.send(5, client.id, "", Map.of(), Map.of()); }
    }

    synchronized void close() {
        for (Server server : List.copyOf(servers)) { server.socket.close(); disconnect(server); }
    }

    private static String text(List<Object> fields, int index) { return (String) fields.get(index); }
    private static List<?> list(List<Object> fields, int index) { return fields.get(index) instanceof List<?> values ? values : List.of(); }
    private static Map<?, ?> payloads(List<Object> fields, int index) { return (Map<?, ?>) fields.get(index); }
}
