package io.floci.az.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TlsConfigSourceCertificateReuseTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci-az.tls.enabled", "true");
        System.setProperty("floci-az.tls.self-signed", "true");
        System.setProperty("floci-az.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-az.tls.enabled");
        System.clearProperty("floci-az.tls.self-signed");
        System.clearProperty("floci-az.storage.persistent-path");
        System.clearProperty("floci-az.hostname");
    }

    @Test
    void existingCertIsReusedWhenHostnamesUnchanged() throws Exception {
        // Generate initial cert
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        assertTrue(Files.exists(certFile));

        // Record modification time then back-date it so we can detect a write
        Instant originalTime = Files.getLastModifiedTime(certFile).toInstant();
        Files.setLastModifiedTime(certFile, FileTime.from(originalTime.minusSeconds(10)));
        Instant modifiedTime = Files.getLastModifiedTime(certFile).toInstant();

        // Instantiate again with same config — cert should be reused
        new TlsConfigSource();

        Instant afterTime = Files.getLastModifiedTime(certFile).toInstant();
        assertEquals(modifiedTime, afterTime, "Cert file should not be overwritten when hostnames are unchanged");
    }

    @Test
    void certIsRegeneratedWhenHostnameChanges() throws Exception {
        // Generate initial cert (no custom hostname)
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-az-selfsigned.crt");
        Instant firstGenTime = Files.getLastModifiedTime(certFile).toInstant();

        // Small sleep to ensure file system timestamp changes
        Thread.sleep(50);

        // Add a custom hostname — should trigger regeneration
        System.setProperty("floci-az.hostname", "floci-az");
        new TlsConfigSource();

        Instant secondGenTime = Files.getLastModifiedTime(certFile).toInstant();
        assertTrue(secondGenTime.isAfter(firstGenTime), "Cert file should be regenerated when hostname config changes");
    }

    @Test
    void certIsRegeneratedWhenMetadataIsMissing() throws Exception {
        // Generate initial cert
        new TlsConfigSource();

        Path certFile     = tempDir.resolve("tls/floci-az-selfsigned.crt");
        Path metadataFile = tempDir.resolve("tls/floci-az-selfsigned.metadata.json");
        assertTrue(Files.exists(metadataFile));

        // Delete metadata to simulate a migration from an older version
        Files.delete(metadataFile);
        Instant beforeTime = Files.getLastModifiedTime(certFile).toInstant();
        Thread.sleep(50);

        new TlsConfigSource();

        Instant afterTime = Files.getLastModifiedTime(certFile).toInstant();
        assertTrue(afterTime.isAfter(beforeTime), "Cert should be regenerated when metadata file is missing");
        assertTrue(Files.exists(metadataFile), "Metadata file should be recreated");
    }
}
