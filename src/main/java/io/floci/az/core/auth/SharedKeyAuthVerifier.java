package io.floci.az.core.auth;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AuthContext;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads a storage request's {@code Authorization: SharedKey|SharedKeyLite <account>:<signature>} header, rebuilds the
 * string Azure signs for it (Authorize with Shared Key), and names the account key that signed it. The account is
 * the one the request URL addresses, as in Azure: a header naming another account fails as a wrong signature.
 *
 * <p>With {@code parity-overrides.strict-storage-keys} a request no current key signed, or whose date is missing or
 * more than 15 minutes old, is refused with Azure's {@code AuthenticationErrorDetail}; without it every signature
 * is accepted, as before, and only the key that signed is recorded.
 */
@ApplicationScoped
public class SharedKeyAuthVerifier implements AuthVerifier {

    /** The service types whose requests storage account keys sign. */
    static final Set<String> STORAGE_SERVICES = Set.of("blob", "queue", "table");

    /** "The storage services ensure that a request is no older than 15 minutes by the time it reaches the service." */
    private static final Duration MAX_REQUEST_AGE = Duration.ofMinutes(15);

    /** Microsoft's Python SDK's emulation of the service's header order: hyphens weigh nothing, then decide ties. */
    private static final Comparator<String> SERVICE_ORDER = Comparator.<String, String>comparing(
            name -> name.replace("-", "")).thenComparing(Comparator.naturalOrder());

    private final StorageAccountKeys keys;
    private final EmulatorConfig config;

    @Inject
    public SharedKeyAuthVerifier(StorageAccountKeys keys, EmulatorConfig config) {
        this.keys = keys;
        this.config = config;
    }

    private record Credential(boolean lite, String account, String signature) {
    }

    @Override
    public Optional<AuthContext> verify(AzureRequest request) {
        Optional<Credential> credential = credential(request.headers().getHeaderString("Authorization"));
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        String account = credential.get().account();
        if (!STORAGE_SERVICES.contains(request.serviceType()) || request.accountName() == null) {
            return Optional.of(new AuthContext(account, AuthType.SHARED_KEY, true));
        }
        List<String> candidates = stringsToSign(request, credential.get().lite());
        // The whole "SharedKey <account>:<signature>" value is compared, so another account's name never signs.
        Optional<String> signer = !account.equals(request.accountName()) ? Optional.empty() : candidates.stream()
                .map(candidate -> keys.signer(request.accountName(), candidate, credential.get().signature()))
                .flatMap(Optional::stream)
                .findFirst();
        Optional<String> failure = Optional.empty();
        if (config.strictStorageKeys()) {
            failure = dateFailure(request);
            if (failure.isEmpty() && signer.isEmpty()) {
                failure = Optional.of("The MAC signature found in the HTTP request '" + credential.get().signature()
                        + "' is not the same as any computed signature. Server used following string to sign: '"
                        + candidates.getFirst() + "'.");
            }
        }
        return Optional.of(new AuthContext(account, AuthType.SHARED_KEY, failure.isEmpty(), Optional.empty(),
                failure.isEmpty() ? signer : Optional.empty(), failure));
    }

    /** Account identity carried by Azure Storage's {@code SharedKey account:signature} header. */
    public static Optional<String> accountName(String authHeader) {
        return credential(authHeader).map(Credential::account);
    }

    private static Optional<Credential> credential(String authHeader) {
        if (authHeader == null) {
            return Optional.empty();
        }
        boolean lite = authHeader.startsWith("SharedKeyLite ");
        if (!lite && !authHeader.startsWith("SharedKey ")) {
            return Optional.empty();
        }
        String value = authHeader.substring(lite ? "SharedKeyLite ".length() : "SharedKey ".length());
        int colonIndex = value.indexOf(':');
        return colonIndex == -1 ? Optional.empty()
                : Optional.of(new Credential(lite, value.substring(0, colonIndex), value.substring(colonIndex + 1)));
    }

    /**
     * The strings the request may have signed, the service's own first. The x-ms- headers are sorted
     * "lexicographically", which "might not always coincide with conventional alphabetical ordering": the first
     * string sorts them as Microsoft's Python SDK emulates the service, the second by code point, as other SDKs do.
     * The two differ only when a hyphen meets a letter.
     */
    private static List<String> stringsToSign(AzureRequest request, boolean lite) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(stringToSign(request, lite, SERVICE_ORDER));
        candidates.add(stringToSign(request, lite, Comparator.naturalOrder()));
        return List.copyOf(candidates);
    }

    private static String stringToSign(AzureRequest request, boolean lite, Comparator<String> headerOrder) {
        String verb = request.method().toUpperCase(Locale.ROOT);
        String resource = "/" + request.accountName() + "/" + (request.rawPath() == null ? "" : request.rawPath());
        String comp = request.queryParams().get("comp");
        String compSuffix = comp == null ? "" : "?comp=" + comp;
        if ("table".equals(request.serviceType())) {
            // "the Date header in this case is never empty even if the request sets the x-ms-date header"
            String date = header(request, "x-ms-date").isEmpty() ? header(request, "Date") : header(request, "x-ms-date");
            return lite
                    ? date + "\n" + resource + compSuffix
                    : String.join("\n", verb, header(request, "Content-MD5"), header(request, "Content-Type"), date,
                            resource + compSuffix);
        }
        // "If you set x-ms-date, construct the signature with an empty value for the Date header."
        String date = request.headers().getHeaderString("x-ms-date") != null ? "" : header(request, "Date");
        String headers = canonicalizedHeaders(request, headerOrder);
        if (lite) {
            return String.join("\n", verb, header(request, "Content-MD5"), header(request, "Content-Type"), date,
                    headers + resource + compSuffix);
        }
        String length = header(request, "Content-Length");
        if ("0".equals(length) && version(request).compareTo("2015-02-21") >= 0) {
            // "the Content-Length field must be an empty string if the content length of the request is zero"
            length = "";
        }
        return String.join("\n", verb, header(request, "Content-Encoding"), header(request, "Content-Language"),
                length, header(request, "Content-MD5"), header(request, "Content-Type"), date,
                header(request, "If-Modified-Since"), header(request, "If-Match"), header(request, "If-None-Match"),
                header(request, "If-Unmodified-Since"), header(request, "Range"),
                headers + resource + canonicalizedQuery(request));
    }

    /** Every x-ms- header, lowercased and sorted, its value's linear whitespace folded outside quotes, then trimmed. */
    private static String canonicalizedHeaders(AzureRequest request, Comparator<String> order) {
        MultivaluedMap<String, String> all = request.headers().getRequestHeaders();
        Map<String, String> headers = new TreeMap<>(order);
        // "Prior to service version 2016-05-31, headers with empty values were omitted from the signature string."
        boolean keepEmpty = version(request).compareTo("2016-05-31") >= 0;
        for (Map.Entry<String, List<String>> entry : all.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!name.startsWith("x-ms-")) {
                continue;
            }
            String value = foldWhitespace(String.join(",", entry.getValue()));
            if (value.isEmpty() && !keepEmpty) {
                continue;
            }
            headers.merge(name, value, (first, second) -> first + "," + second);
        }
        StringBuilder result = new StringBuilder();
        headers.forEach((name, value) -> result.append(name).append(':').append(value).append('\n'));
        return result.toString();
    }

    /** Query parameters by lowercased name, sorted; a parameter's values sorted and comma-joined. */
    private static String canonicalizedQuery(AzureRequest request) {
        Map<String, List<String>> parameters = new TreeMap<>();
        request.queryParamsMulti().forEach((name, values) ->
                parameters.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).addAll(values));
        StringBuilder result = new StringBuilder();
        parameters.forEach((name, values) -> result.append('\n').append(name).append(':')
                .append(String.join(",", values.stream().sorted().toList())));
        return result.toString();
    }

    private static String foldWhitespace(String value) {
        StringBuilder folded = new StringBuilder();
        boolean quoted = false;
        boolean space = false;
        for (char c : value.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (!quoted && (c == ' ' || c == '\t' || c == '\r' || c == '\n')) {
                space = true;
                continue;
            }
            if (space && !folded.isEmpty()) {
                folded.append(' ');
            }
            space = false;
            folded.append(c);
        }
        return folded.toString();
    }

    /** Why the request's date is refused, if it is: missing, unreadable, or more than 15 minutes old. */
    private static Optional<String> dateFailure(AzureRequest request) {
        String date = request.headers().getHeaderString("x-ms-date");
        if (date == null) {
            date = request.headers().getHeaderString("Date");
        }
        if (date == null) {
            return Optional.of("The Date header in the request is incorrect.");
        }
        try {
            Instant sent = ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (sent.isBefore(Instant.now().minus(MAX_REQUEST_AGE))) {
                return Optional.of("Request date header too old: '" + date + "'");
            }
            return Optional.empty();
        } catch (DateTimeParseException e) {
            return Optional.of("The Date header in the request is incorrect.");
        }
    }

    /** The request's service version; a request without one is read as the current version. */
    private static String version(AzureRequest request) {
        String version = request.headers().getHeaderString("x-ms-version");
        return version == null ? "9999-12-31" : version;
    }

    private static String header(AzureRequest request, String name) {
        String value = request.headers().getHeaderString(name);
        return value == null ? "" : value;
    }
}
