package io.floci.az.compat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.VersionInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility smoke using the real Apache Hadoop ABFS 3.3.4 implementation.
 *
 * <p>The Docker compatibility harness maps {@code devstoreaccount1.dfs.core.windows.net}
 * to this test container's loopback address. A transparent TCP forwarder on port 80
 * relays the original HTTP stream to floci-az:4577, preserving Hadoop's DFS Host header
 * and wire shape without adding emulator-only request headers or routing heuristics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Hadoop ABFS 3.3.4 Compatibility")
class HadoopAbfsCompatibilityTest {

    private static final String ACCOUNT_HOST = EmulatorConfig.ACCOUNT + ".dfs.core.windows.net";
    private TcpForwarder forwarder;

    @BeforeAll
    void setup() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("FLOCI_AZ_ABFS_LOOPBACK")),
                "Hadoop ABFS compatibility test requires the Docker loopback host mapping");
        EmulatorConfig.assumeEmulatorRunning();

        URI emulator = URI.create(EmulatorConfig.httpBase());
        int targetPort = emulator.getPort() > 0 ? emulator.getPort() : 80;
        forwarder = new TcpForwarder(80, emulator.getHost(), targetPort);
        forwarder.start();
    }

    @AfterAll
    void teardown() throws Exception {
        if (forwarder != null) {
            forwarder.close();
        }
    }

    @Test
    @DisplayName("filesystem: real Hadoop 3.3.4 ABFS client exercises DFS path semantics")
    void hadoopAbfs334FilesystemSmoke() throws Exception {
        String filesystem = "hadoop-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        URI uri = URI.create("abfs://" + filesystem + "@" + ACCOUNT_HOST);

        assertEquals("3.3.4", VersionInfo.getVersion());

        Configuration conf = new Configuration();
        conf.set("fs.abfs.impl", "org.apache.hadoop.fs.azurebfs.AzureBlobFileSystem");
        conf.set("fs.azure.account.key." + ACCOUNT_HOST, EmulatorConfig.DEV_KEY);
        // Hadoop 3.3.4 defaults fs.azure.always.use.https=true even for abfs://.
        // This Docker-only compatibility harness deliberately forwards plaintext HTTP
        // from ACCOUNT_HOST:80 to floci-az:4577 so the original DFS Host header is preserved.
        conf.setBoolean("fs.azure.always.use.https", false);
        conf.setBoolean("fs.azure.createRemoteFileSystemDuringInitialization", true);
        conf.setBoolean("fs.azure.enable.conditional.create.overwrite", true);
        conf.unset("fs.azure.account.hns.enabled." + ACCOUNT_HOST);
        conf.unset("fs.azure.account.hns.enabled");

        try (FileSystem fs = FileSystem.newInstance(uri, conf)) {
            assertEquals("org.apache.hadoop.fs.azurebfs.AzureBlobFileSystem", fs.getClass().getName());
            assertTrue(fs.getFileStatus(new Path("/")).isDirectory());

            Path dir = new Path("/compat");
            Path file = new Path(dir, "file.txt");
            assertTrue(fs.mkdirs(dir));

            write(fs, file, "first");
            write(fs, file, "second"); // Hadoop's default conditional overwrite flow.
            assertEquals("second", read(fs, file));

            byte[] xattr = "v8".getBytes(StandardCharsets.UTF_8);
            fs.setXAttr(file, "user.floci_smoke", xattr);
            assertArrayEquals(xattr, fs.getXAttr(file, "user.floci_smoke"));
            assertEquals("second", read(fs, file), "setting an XAttr must not truncate file content");

            FileStatus[] exact = fs.listStatus(file);
            assertEquals(1, exact.length);
            assertFalse(exact[0].isDirectory());
            assertEquals("file.txt", exact[0].getPath().getName());

            Path renamed = new Path(dir, "renamed.txt");
            assertTrue(fs.rename(file, renamed));
            assertEquals("second", read(fs, renamed));

            assertFalse(fs.delete(new Path(dir, "missing.txt"), true));
            assertTrue(fs.delete(dir, true));
        }
    }

    private static void write(FileSystem fs, Path path, String value) throws IOException {
        try (OutputStream out = fs.create(path, true)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(FileSystem fs, Path path) throws IOException {
        try (InputStream in = fs.open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Simple byte-for-byte TCP relay used only by this compatibility test. */
    private static final class TcpForwarder implements AutoCloseable {
        private final int listenPort;
        private final String targetHost;
        private final int targetPort;
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hadoop-abfs-tcp-forwarder");
            thread.setDaemon(true);
            return thread;
        });
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean running = new AtomicBoolean();
        private ServerSocket serverSocket;

        private TcpForwarder(int listenPort, String targetHost, int targetPort) {
            this.listenPort = listenPort;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }

        void start() throws IOException {
            serverSocket = new ServerSocket(
                    listenPort,
                    50,
                    InetAddress.getByName("127.0.0.1"));
            running.set(true);
            executor.submit(this::acceptLoop);
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    Socket upstream = new Socket(targetHost, targetPort);
                    sockets.add(client);
                    sockets.add(upstream);
                    executor.submit(() -> relay(client, upstream));
                } catch (IOException e) {
                    if (running.get()) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        private void relay(Socket client, Socket upstream) {
            AtomicBoolean closed = new AtomicBoolean();
            executor.submit(() -> copyAndClose(client, upstream, closed));
            executor.submit(() -> copyAndClose(upstream, client, closed));
        }

        private void copyAndClose(Socket source, Socket destination, AtomicBoolean closed) {
            try {
                source.getInputStream().transferTo(destination.getOutputStream());
                destination.getOutputStream().flush();
            } catch (IOException ignored) {
                // Connection shutdown races are expected when either side closes an HTTP keep-alive socket.
            } finally {
                if (closed.compareAndSet(false, true)) {
                    sockets.remove(source);
                    sockets.remove(destination);
                    closeQuietly(source);
                    closeQuietly(destination);
                }
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            if (serverSocket != null) {
                serverSocket.close();
            }
            sockets.forEach(TcpForwarder::closeQuietly);
            sockets.clear();
            executor.shutdownNow();
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort test cleanup.
            }
        }
    }
}
