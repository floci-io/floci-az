package io.floci.az.services.cosmos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Codec for the RecordIO/HybridRow transactional-batch format used by Microsoft.Azure.Cosmos. */
final class CosmosHybridRowBatchCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] SEGMENT_HEADER = {
        (byte) 0x81, (byte) 0xf0, (byte) 0xd8, (byte) 0xff, 0x7f, 0x01, 0x0a, 0x00, 0x00, 0x00
    };
    private static final byte[] RECORD_HEADER_PREFIX = {
        (byte) 0x81, (byte) 0xf1, (byte) 0xd8, (byte) 0xff, 0x7f
    };
    private static final byte[] OPERATION_ROW_HEADER = {
        (byte) 0x81, 0x70, 0x54, (byte) 0xe1, 0x7f
    };
    private static final byte[] RESULT_ROW_HEADER = {
        (byte) 0x81, 0x71, 0x54, (byte) 0xe1, 0x7f
    };

    private static final int RECORD_HEADER_LENGTH = 13;
    private static final int RECORD_LENGTH_OFFSET = 5;
    private static final int RECORD_CRC_OFFSET = 9;
    private static final int ID_VARIABLE_INDEX = 2;
    private static final int RESOURCE_BODY_VARIABLE_INDEX = 4;
    private static final int TYPE_INT32 = 7;
    private static final int TYPE_UINT32 = 11;
    private static final int TYPE_FLOAT64 = 16;
    private static final int TYPE_UTF8 = 20;
    private static final int TYPE_BOOLEAN_FALSE = 2;
    private static final int TYPE_BOOLEAN_TRUE = 3;

    private static final int TOKEN_IF_MATCH = 9;
    private static final int TOKEN_IF_NONE_MATCH = 10;
    private static final int TOKEN_RETRY_AFTER_MILLISECONDS = 5;
    private static final int TOKEN_REQUEST_CHARGE = 6;

    private static final Map<Integer, String> OPERATION_TYPES = Map.of(
            0, "Create",
            1, "Patch",
            2, "Read",
            4, "Delete",
            5, "Replace",
            20, "Upsert");

    private CosmosHybridRowBatchCodec() {
    }

    static boolean isHybridRow(byte[] payload) {
        return startsWith(payload, 0, SEGMENT_HEADER);
    }

    static List<Map<String, Object>> decodeOperations(byte[] payload) throws IOException {
        if (!isHybridRow(payload)) {
            throw new IOException("Missing HybridRow RecordIO segment header");
        }

        List<Map<String, Object>> operations = new ArrayList<>();
        int offset = SEGMENT_HEADER.length;
        while (offset < payload.length) {
            if (!startsWith(payload, offset, RECORD_HEADER_PREFIX)
                    || payload.length - offset < RECORD_HEADER_LENGTH) {
                throw new IOException("Invalid HybridRow RecordIO record header");
            }

            int bodyLength = readInt32(payload, offset + RECORD_LENGTH_OFFSET);
            int bodyOffset = offset + RECORD_HEADER_LENGTH;
            if (bodyLength <= 0 || bodyLength > payload.length - bodyOffset) {
                throw new IOException("Invalid HybridRow RecordIO record length");
            }

            long expectedCrc = Integer.toUnsignedLong(readInt32(payload, offset + RECORD_CRC_OFFSET));
            CRC32 crc = new CRC32();
            crc.update(payload, bodyOffset, bodyLength);
            if (crc.getValue() != expectedCrc) {
                throw new IOException("HybridRow RecordIO CRC mismatch");
            }

            operations.add(decodeOperation(payload, bodyOffset, bodyLength));
            offset = bodyOffset + bodyLength;
        }

        if (operations.isEmpty()) {
            throw new IOException("HybridRow batch contains no operations");
        }
        return operations;
    }

    static byte[] encodeResults(List<Map<String, Object>> results) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEGMENT_HEADER);
        for (Map<String, Object> result : results) {
            byte[] body = encodeResult(result);
            output.write(RECORD_HEADER_PREFIX);
            writeInt32(output, body.length);

            CRC32 crc = new CRC32();
            crc.update(body);
            writeInt32(output, (int) crc.getValue());
            output.write(body);
        }
        return output.toByteArray();
    }

    private static Map<String, Object> decodeOperation(byte[] payload, int bodyOffset, int bodyLength)
            throws IOException {
        int end = bodyOffset + bodyLength;
        Cursor cursor = new Cursor(payload, bodyOffset, end);
        cursor.expect(OPERATION_ROW_HEADER);

        int presence = cursor.readUnsignedByte();
        if ((presence & 0x03) != 0x03) {
            throw new IOException("HybridRow operation is missing mandatory fields");
        }

        String operationType = OPERATION_TYPES.get(cursor.readInt32());
        int resourceType = cursor.readInt32();
        if (operationType == null || resourceType != 2) {
            throw new IOException("Unsupported HybridRow batch operation");
        }

        byte[][] variableColumns = new byte[5][];
        for (int index = 0; index < variableColumns.length; index++) {
            if ((presence & (1 << (index + 2))) != 0) {
                variableColumns[index] = cursor.readVariable();
            }
        }

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationType", operationType);
        if (variableColumns[ID_VARIABLE_INDEX] != null) {
            operation.put("id", new String(variableColumns[ID_VARIABLE_INDEX], StandardCharsets.UTF_8));
        }
        if (variableColumns[RESOURCE_BODY_VARIABLE_INDEX] != null) {
            operation.put("resourceBody", MAPPER.readValue(variableColumns[RESOURCE_BODY_VARIABLE_INDEX],
                    new TypeReference<Map<String, Object>>() {}));
        }

        while (cursor.hasRemaining()) {
            int type = cursor.readUnsignedByte();
            int token = cursor.readVarUInt();
            switch (type) {
                case TYPE_UTF8 -> {
                    String value = new String(cursor.readVariable(), StandardCharsets.UTF_8);
                    if (token == TOKEN_IF_MATCH) {
                        operation.put("ifMatch", value);
                    } else if (token == TOKEN_IF_NONE_MATCH) {
                        operation.put("ifNoneMatch", value);
                    }
                }
                case TYPE_INT32, TYPE_UINT32 -> cursor.skip(4);
                case TYPE_FLOAT64 -> cursor.skip(8);
                case TYPE_BOOLEAN_FALSE, TYPE_BOOLEAN_TRUE -> {
                    // The value is carried by the type code.
                }
                default -> throw new IOException("Unsupported HybridRow sparse field type");
            }
        }
        return operation;
    }

    private static byte[] encodeResult(Map<String, Object> result) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(RESULT_ROW_HEADER);

        String etag = result.get("eTag") instanceof String value ? value : null;
        byte[] resourceBody = result.get("resourceBody") instanceof Map<?, ?> value
                ? MAPPER.writeValueAsBytes(value) : null;
        int presence = 0x03;
        if (etag != null) {
            presence |= 0x04;
        }
        if (resourceBody != null) {
            presence |= 0x08;
        }
        body.write(presence);

        writeInt32(body, ((Number) result.get("statusCode")).intValue());
        writeInt32(body, ((Number) result.getOrDefault("subStatusCode", 0)).intValue());
        if (etag != null) {
            writeVariable(body, etag.getBytes(StandardCharsets.UTF_8));
        }
        if (resourceBody != null) {
            writeVariable(body, resourceBody);
        }

        body.write(TYPE_UINT32);
        writeVarUInt(body, TOKEN_RETRY_AFTER_MILLISECONDS);
        writeInt32(body, 0);

        body.write(TYPE_FLOAT64);
        writeVarUInt(body, TOKEN_REQUEST_CHARGE);
        writeFloat64(body, ((Number) result.getOrDefault("requestCharge", 1.0)).doubleValue());
        return body.toByteArray();
    }

    private static boolean startsWith(byte[] value, int offset, byte[] prefix) {
        if (offset < 0 || value.length - offset < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static int readInt32(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void writeInt32(ByteArrayOutputStream output, int value) {
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static void writeFloat64(ByteArrayOutputStream output, double value) {
        output.writeBytes(ByteBuffer.allocate(Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array());
    }

    private static void writeVariable(ByteArrayOutputStream output, byte[] value) {
        writeVarUInt(output, value.length);
        output.writeBytes(value);
    }

    private static void writeVarUInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7f;
            remaining >>>= 7;
            output.write(remaining == 0 ? next : next | 0x80);
        } while (remaining != 0);
    }

    private static final class Cursor {
        private final byte[] payload;
        private final int end;
        private int offset;

        private Cursor(byte[] payload, int offset, int end) {
            this.payload = payload;
            this.offset = offset;
            this.end = end;
        }

        private boolean hasRemaining() {
            return offset < end;
        }

        private void expect(byte[] expected) throws IOException {
            if (!startsWith(payload, offset, expected) || expected.length > end - offset) {
                throw new IOException("Unexpected HybridRow schema");
            }
            offset += expected.length;
        }

        private int readUnsignedByte() throws IOException {
            require(1);
            return Byte.toUnsignedInt(payload[offset++]);
        }

        private int readInt32() throws IOException {
            require(Integer.BYTES);
            int value = CosmosHybridRowBatchCodec.readInt32(payload, offset);
            offset += Integer.BYTES;
            return value;
        }

        private int readVarUInt() throws IOException {
            int value = 0;
            for (int shift = 0; shift < Integer.SIZE; shift += 7) {
                int next = readUnsignedByte();
                value |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("Invalid HybridRow variable-length integer");
        }

        private byte[] readVariable() throws IOException {
            int length = readVarUInt();
            require(length);
            byte[] value = new byte[length];
            System.arraycopy(payload, offset, value, 0, length);
            offset += length;
            return value;
        }

        private void skip(int length) throws IOException {
            require(length);
            offset += length;
        }

        private void require(int length) throws IOException {
            if (length < 0 || length > end - offset) {
                throw new IOException("Truncated HybridRow value");
            }
        }
    }
}
