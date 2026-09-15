package io.floci.az.services.signalr;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Azure service protocol: a base-128 length prefix followed by a MessagePack array. */
final class SignalRProtocol {
    private static final int MAX_PAYLOAD = 1024 * 1024;
    private byte[] pending = new byte[0];

    void accept(byte[] bytes, Consumer<List<Object>> messages) throws IOException {
        // A transport chunk can contain several frames; enforce the limit on each declared payload.
        byte[] input = Arrays.copyOf(pending, pending.length + bytes.length);
        System.arraycopy(bytes, 0, input, pending.length, bytes.length);
        int offset = 0;
        while (offset < input.length) {
            int start = offset;
            int length = 0;
            int shift = 0;
            boolean complete = false;
            while (offset < input.length) {
                int value = input[offset++] & 255;
                if (shift == 28 && (value & 248) != 0) {
                    throw new IOException("Invalid service message length");
                }
                length |= (value & 127) << shift;
                if ((value & 128) == 0) {
                    complete = true;
                    break;
                }
                shift += 7;
            }
            if (length > MAX_PAYLOAD) {
                throw new IOException("Service message exceeds 1 MiB");
            }
            if (!complete || input.length - offset < length) {
                offset = start;
                break;
            }
            try (var unpacker = MessagePack.newDefaultUnpacker(input, offset, length)) {
                Object decoded = decode(unpacker.unpackValue());
                if (!(decoded instanceof List<?> values) || values.isEmpty() || unpacker.hasNext()) {
                    throw new IOException("Expected one service message array");
                }
                messages.accept(new ArrayList<>(values));
            }
            offset += length;
        }
        pending = Arrays.copyOfRange(input, offset, input.length);
    }

    static byte[] frame(Object... values) {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            pack(packer, Arrays.asList(values));
            byte[] payload = packer.toByteArray();
            var output = new ByteArrayOutputStream(payload.length + 5);
            int remaining = payload.length;
            do {
                int digit = remaining & 127;
                remaining >>>= 7;
                output.write(digit | (remaining == 0 ? 0 : 128));
            } while (remaining != 0);
            output.writeBytes(payload);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode service message", e);
        }
    }

    private static void pack(MessagePacker packer, Object value) throws IOException {
        switch (value) {
            case null -> packer.packNil();
            case String text -> packer.packString(text);
            case Number number -> packer.packLong(number.longValue());
            case Boolean flag -> packer.packBoolean(flag);
            case byte[] bytes -> { packer.packBinaryHeader(bytes.length); packer.writePayload(bytes); }
            case List<?> list -> {
                packer.packArrayHeader(list.size());
                for (Object item : list) { pack(packer, item); }
            }
            case SignalRTokens.Claims claims -> {
                packer.packMapHeader(claims.values().size());
                for (var claim : claims.values()) { packer.packString(claim.getKey()); packer.packString(claim.getValue()); }
            }
            case Map<?, ?> map -> {
                packer.packMapHeader(map.size());
                for (var entry : map.entrySet()) { pack(packer, entry.getKey()); pack(packer, entry.getValue()); }
            }
            default -> throw new IOException("Unsupported service message value");
        }
    }

    private static Object decode(Value value) throws IOException {
        return switch (value.getValueType()) {
            case NIL -> null;
            case BOOLEAN -> value.asBooleanValue().getBoolean();
            case INTEGER -> value.asIntegerValue().asLong();
            case STRING -> value.asStringValue().asString();
            case BINARY -> value.asBinaryValue().asByteArray();
            case ARRAY -> {
                List<Object> items = new ArrayList<>();
                for (Value item : value.asArrayValue()) { items.add(decode(item)); }
                yield items;
            }
            case MAP -> {
                Map<Object, Object> items = new LinkedHashMap<>();
                for (var item : value.asMapValue().entrySet()) { items.put(decode(item.getKey()), decode(item.getValue())); }
                yield items;
            }
            default -> throw new IOException("Unsupported service message value");
        };
    }
}
