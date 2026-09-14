package io.floci.az.services.signalr;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SignalRProtocolTest {
    @Test
    void handlesFragmentedAndCoalescedFrames() throws Exception {
        byte[] first = SignalRProtocol.frame(6, "connection", new byte[512], Map.of());
        byte[] second = SignalRProtocol.frame(3, "status", "1");
        var reader = new SignalRProtocol();
        List<List<Object>> messages = new ArrayList<>();
        reader.accept(Arrays.copyOf(first, 1), messages::add);
        assertTrue(messages.isEmpty());
        byte[] rest = Arrays.copyOfRange(first, 1, first.length + second.length);
        System.arraycopy(second, 0, rest, first.length - 1, second.length);
        reader.accept(rest, messages::add);
        assertEquals(2, messages.size());
        assertEquals(6L, messages.getFirst().getFirst());
        assertArrayEquals(new byte[512], (byte[]) messages.getFirst().get(2));
        assertEquals(List.of(3L, "status", "1"), messages.getLast());
    }

    @Test
    void rejectsOverflowingLengthPrefix() {
        assertThrows(IOException.class, () -> new SignalRProtocol().accept(
                new byte[]{-1, -1, -1, -1, -1, 0}, ignored -> fail()));
    }

    @Test
    void preservesBinaryProtocolPayloads() throws Exception {
        byte[] binary = new byte[]{0, -1, -2, 1};
        new SignalRProtocol().accept(SignalRProtocol.frame(10, List.of(), Map.of("messagepack", binary)), fields ->
                assertArrayEquals(binary, (byte[]) ((Map<?, ?>) fields.get(2)).get("messagepack")));
    }
}
