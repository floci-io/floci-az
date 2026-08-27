package io.floci.az.services.cosmos;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmosHybridRowBatchCodecTest {

    private static final String PATCH_CAPTURE =
            "gfDY/38BCgAAAIHx2P9/dAAAAM44guiBcFThf1MBAAAAAgAAAANvbmVheyJvcGVyYXRpb25zIjpb"
                    + "eyJvcCI6InNldCIsInBhdGgiOiIva2luZCIsInZhbHVlIjoieiJ9LHsib3AiOiJpbmNyIiwicGF0aCI6"
                    + "Ii9jb3VudCIsInZhbHVlIjoxMH1dfQ==";
    private static final String REPLACE_DELETE_CAPTURE =
            "gfDY/38BCgAAAIHx2P9/TQAAAJKKVRCBcFThf1MFAAAAAgAAAANvbmU6eyJpZCI6Im9uZSIsInRl"
                    + "bmFudCI6InUxIiwia2luZCI6InJlcGxhY2VtZW50IiwiY291bnQiOjk5fYHx2P9/FgAAAAtBUkGBcFTh"
                    + "fxMEAAAAAgAAAAdtaXNzaW5n";
    private static final String IF_MATCH_DELETE_CAPTURE =
            "gfDY/38BCgAAAIHx2P9/GgAAADpfNJeBcFThfxMEAAAAAgAAAANvbmUUCQVzdGFsZQ==";
    private static final String CREATE_READ_UPSERT_CAPTURE =
            "gfDY/38BCgAAAIHx2P9/MgAAAIy0GfCBcFThf0MAAAAAAgAAACN7ImlkIjoiY3JlYXRlZCIs"
                    + "InBrIjoicCIsInZhbHVlIjoxfYHx2P9/FgAAAI7kXlCBcFThfxMCAAAAAgAAAAdjcmVhdGVk"
                    + "gfHY/38yAAAAJIqEvIFwVOF/QxQAAAACAAAAI3siaWQiOiJjcmVhdGVkIiwicGsiOiJwIiwidm"
                    + "FsdWUiOjJ9";

    @Test
    void decodesPatchBodyCapturedFromDotnetSdk() throws IOException {
        List<Map<String, Object>> operations = CosmosHybridRowBatchCodec.decodeOperations(decode(PATCH_CAPTURE));

        assertEquals(1, operations.size());
        Map<String, Object> patch = operations.getFirst();
        assertEquals("Patch", patch.get("operationType"));
        assertEquals("one", patch.get("id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patchOperations =
                (List<Map<String, Object>>) ((Map<String, Object>) patch.get("resourceBody")).get("operations");
        assertEquals(List.of("set", "incr"), patchOperations.stream().map(value -> value.get("op")).toList());
        assertEquals(10, patchOperations.get(1).get("value"));
    }

    @Test
    void decodesMultipleRecordsAndSparseEtag() throws IOException {
        List<Map<String, Object>> operations =
                CosmosHybridRowBatchCodec.decodeOperations(decode(REPLACE_DELETE_CAPTURE));

        assertEquals(2, operations.size());
        assertEquals("Replace", operations.get(0).get("operationType"));
        assertEquals("replacement",
                ((Map<?, ?>) operations.get(0).get("resourceBody")).get("kind"));
        assertEquals("Delete", operations.get(1).get("operationType"));
        assertEquals("missing", operations.get(1).get("id"));

        Map<String, Object> conditionalDelete =
                CosmosHybridRowBatchCodec.decodeOperations(decode(IF_MATCH_DELETE_CAPTURE)).getFirst();
        assertEquals("stale", conditionalDelete.get("ifMatch"));
    }

    @Test
    void decodesCreateReadAndUpsertOpcodes() throws IOException {
        List<Map<String, Object>> operations =
                CosmosHybridRowBatchCodec.decodeOperations(decode(CREATE_READ_UPSERT_CAPTURE));

        assertEquals(List.of("Create", "Read", "Upsert"),
                operations.stream().map(operation -> operation.get("operationType")).toList());
        assertEquals(1, ((Map<?, ?>) operations.get(0).get("resourceBody")).get("value"));
        assertEquals("created", operations.get(1).get("id"));
        assertEquals(2, ((Map<?, ?>) operations.get(2).get("resourceBody")).get("value"));
    }

    @Test
    void rejectsCorruptRecordCrc() {
        byte[] payload = decode(PATCH_CAPTURE);
        payload[payload.length - 1] ^= 1;

        IOException exception = assertThrows(IOException.class,
                () -> CosmosHybridRowBatchCodec.decodeOperations(payload));
        assertEquals("HybridRow RecordIO CRC mismatch", exception.getMessage());
    }

    @Test
    void encodesRecordIoResultsWithValidLengthsAndCrcs() throws IOException {
        List<Map<String, Object>> results = List.of(
                Map.of("statusCode", 200, "subStatusCode", 0, "requestCharge", 1.0,
                        "eTag", "\"etag\"", "resourceBody", Map.of("id", "one")),
                Map.of("statusCode", 404, "subStatusCode", 0, "requestCharge", 1.0));

        byte[] encoded = CosmosHybridRowBatchCodec.encodeResults(results);
        assertTrue(CosmosHybridRowBatchCodec.isHybridRow(encoded));

        int offset = 10;
        for (int record = 0; record < results.size(); record++) {
            assertArrayEquals(new byte[] {(byte) 0x81, (byte) 0xf1, (byte) 0xd8, (byte) 0xff, 0x7f},
                    Arrays.copyOfRange(encoded, offset, offset + 5));
            int length = readInt32(encoded, offset + 5);
            long expectedCrc = Integer.toUnsignedLong(readInt32(encoded, offset + 9));
            CRC32 crc = new CRC32();
            crc.update(encoded, offset + 13, length);
            assertEquals(expectedCrc, crc.getValue());
            assertArrayEquals(new byte[] {(byte) 0x81, 0x71, 0x54, (byte) 0xe1, 0x7f},
                    Arrays.copyOfRange(encoded, offset + 13, offset + 18));
            offset += 13 + length;
        }
        assertEquals(encoded.length, offset);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static int readInt32(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
