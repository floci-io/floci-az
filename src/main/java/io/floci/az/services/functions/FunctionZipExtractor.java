package io.floci.az.services.functions;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts ZIP bytes to a target directory, guarding against path traversal.
 */
@ApplicationScoped
public class FunctionZipExtractor {

    private static final Logger LOG = Logger.getLogger(FunctionZipExtractor.class);

    public void extractTo(byte[] zipBytes, Path targetDir) throws IOException {
        Path absTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(absTarget);

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/")) {
                    LOG.warnv("Skipping suspicious ZIP entry: {0}", name);
                    zis.closeEntry();
                    continue;
                }
                Path target = absTarget.resolve(name).normalize();
                if (!target.startsWith(absTarget)) {
                    LOG.warnv("Skipping out-of-bounds ZIP entry: {0}", name);
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
        LOG.debugv("Extracted ZIP ({0} bytes) to: {1}", zipBytes.length, absTarget);
    }
}
