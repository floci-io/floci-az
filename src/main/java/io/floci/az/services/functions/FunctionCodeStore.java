package io.floci.az.services.functions;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Manages function code packages on disk.
 * Each function's code lives at {basePath}/{account}/{appName}/{funcName}/.
 */
@ApplicationScoped
public class FunctionCodeStore {

    private static final Logger LOG = Logger.getLogger(FunctionCodeStore.class);

    private final EmulatorConfig config;
    private final FunctionZipExtractor extractor;

    @Inject
    public FunctionCodeStore(EmulatorConfig config, FunctionZipExtractor extractor) {
        this.config    = config;
        this.extractor = extractor;
    }

    /**
     * Extracts the ZIP into the code directory and returns the absolute path.
     */
    public Path storeCode(String account, String appName, String funcName, byte[] zipBytes)
            throws IOException {
        Path dir = codeDir(account, appName, funcName);
        deleteDir(dir);
        extractor.extractTo(zipBytes, dir);
        LOG.infov("Stored code for {0}/{1}/{2} at {3}", account, appName, funcName, dir);
        return dir;
    }

    public Path getCodePath(String account, String appName, String funcName) {
        return codeDir(account, appName, funcName);
    }

    public void deleteCode(String account, String appName, String funcName) {
        try {
            deleteDir(codeDir(account, appName, funcName));
        } catch (IOException e) {
            LOG.warnv("Failed to delete code for {0}/{1}/{2}: {3}", account, appName, funcName, e.getMessage());
        }
    }

    public void deleteApp(String account, String appName) {
        try {
            deleteDir(appDir(account, appName));
        } catch (IOException e) {
            LOG.warnv("Failed to delete app code for {0}/{1}: {2}", account, appName, e.getMessage());
        }
    }

    private Path codeDir(String account, String appName, String funcName) {
        return basePath().resolve(sanitize(account)).resolve(sanitize(appName)).resolve(sanitize(funcName));
    }

    private Path appDir(String account, String appName) {
        return basePath().resolve(sanitize(account)).resolve(sanitize(appName));
    }

    private Path basePath() {
        return Path.of(config.services().functions().codePath()
                .replace("${user.home}", System.getProperty("user.home")));
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }
}
