package io.floci.az.services.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.functions.FunctionModels.FunctionDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages function code packages on disk.
 * Each function's code lives at {basePath}/{account}/{appName}/{funcName}/.
 */
@ApplicationScoped
public class FunctionCodeStore {

    private static final Logger LOG = Logger.getLogger(FunctionCodeStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ROUTE_DECORATOR = Pattern.compile(
            "@app\\.route\\s*\\((.*?)\\)\\s*(?:\\r?\\n\\s*@[^\\r\\n]+)*\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
            Pattern.DOTALL);
    private static final Pattern ROUTE_VALUE = Pattern.compile(
            "\\broute\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");

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

    public boolean isPackageRootLayout(Path codePath) {
        return Files.isRegularFile(codePath.resolve("function_app.py"));
    }

    public String routePrefix(Path codePath) throws IOException {
        Path hostJson = codePath.resolve("host.json");
        if (!Files.isRegularFile(hostJson)) {
            return null;
        }
        JsonNode root = MAPPER.readTree(Files.readString(hostJson, StandardCharsets.UTF_8));
        JsonNode routePrefix = root.path("extensions").path("http").get("routePrefix");
        return routePrefix != null && routePrefix.isTextual() ? routePrefix.asText() : null;
    }

    public String functionRoute(Path codePath, String handler) throws IOException {
        if (!isPackageRootLayout(codePath) || handler == null || handler.isBlank()) {
            return null;
        }
        String methodName = handler.substring(handler.lastIndexOf('.') + 1);
        String source = Files.readString(codePath.resolve("function_app.py"), StandardCharsets.UTF_8);
        Matcher decorator = ROUTE_DECORATOR.matcher(source);
        while (decorator.find()) {
            if (!methodName.equals(decorator.group(2))) {
                continue;
            }
            Matcher route = ROUTE_VALUE.matcher(decorator.group(1));
            return route.find() ? route.group(1) : methodName;
        }
        return null;
    }

    public FunctionDefinition normalize(FunctionDefinition definition) throws IOException {
        if (definition.codeLocalPath() == null) {
            return definition;
        }
        Path codePath = Path.of(definition.codeLocalPath());
        if (!isPackageRootLayout(codePath)) {
            return definition;
        }
        String prefix = definition.routePrefix() == null
                ? routePrefix(codePath) : definition.routePrefix();
        String route = definition.functionRoute() == null
                ? functionRoute(codePath, definition.handler()) : definition.functionRoute();
        if (definition.packageRootLayout() && Objects.equals(prefix, definition.routePrefix())
                && Objects.equals(route, definition.functionRoute())) {
            return definition;
        }
        return new FunctionDefinition(
                definition.appName(), definition.funcName(), definition.accountName(),
                definition.runtime(), definition.linuxFxVersion(), definition.handler(),
                definition.timeoutSeconds(), definition.environment(), definition.codeLocalPath(),
                definition.createdAt(), true, prefix, route);
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
