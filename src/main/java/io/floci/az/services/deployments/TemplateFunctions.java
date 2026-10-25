package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.floci.az.services.deployments.TemplateValues.NODES;
import static io.floci.az.services.deployments.TemplateValues.deepEquals;
import static io.floci.az.services.deployments.TemplateValues.isNull;
import static io.floci.az.services.deployments.TemplateValues.lower;
import static io.floci.az.services.deployments.TemplateValues.number;
import static io.floci.az.services.deployments.TemplateValues.property;
import static io.floci.az.services.deployments.TemplateValues.text;
import static io.floci.az.services.deployments.TemplateValues.typeName;

/**
 * The template language's function library, called by {@link ExpressionEvaluator} with arguments already evaluated.
 * Semantics follow {@code azure-resource-manager/templates/template-functions-*.md}; {@code if}, {@code and} and
 * {@code or} evaluate lazily and live in the evaluator.
 */
final class TemplateFunctions {

    /** Functions Azure documents that floci-az does not implement; calling one is an error, never a guess. */
    static final Set<String> UNSUPPORTED = Set.of(
        "providers", "references", "pickzones", "managementgroup", "managementgroupresourceid",
        "deployer", "roledefinitions", "lambda", "filter", "map", "reduce", "sort", "toobject", "groupby",
        "mapvalues", "parsecidr", "cidrsubnet", "cidrhost");

    /** The {@code guid()} namespace ({@code template-functions-string.md}, guid). */
    private static final UUID GUID_NAMESPACE = UUID.fromString("11fb06fb-712d-4ddd-98c7-e71bbd588830");

    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

    /** The {@code dateTimeAdd} duration: an optional sign, then {@code PnYnMnWnDTnHnMnS}. */
    private static final Pattern DURATION = Pattern.compile(
        "(-)?P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?");

    private static final DateTimeFormatter ISO_OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private TemplateFunctions() {
    }

    static JsonNode call(String name, List<JsonNode> args, TemplateScope scope) {
        String key = lower(name);
        if (key.contains(".")) {
            return scope.userFunction(name, args);
        }
        if (UNSUPPORTED.contains(key)) {
            throw TemplateException.invalid("The template function '" + name + "' is not supported by floci-az.");
        }
        if (key.startsWith("list")) {
            return listFunction(name, args, scope);
        }
        return switch (key) {
            case "parameters" -> scope.parameter(string(name, args, 0, 1, 1));
            case "variables" -> scope.variable(string(name, args, 0, 1, 1));
            case "resourcegroup" -> scopeObject(name, args, scope.resourceGroup());
            case "subscription" -> scopeObject(name, args, scope.subscription());
            case "tenant" -> scopeObject(name, args, scope.tenant());
            case "deployment" -> scopeObject(name, args, scope.deployment());
            case "environment" -> scopeObject(name, args, scope.environment());
            case "resourceid" -> NODES.textNode(resourceId(name, args, scope));
            case "subscriptionresourceid" -> NODES.textNode(subscriptionResourceId(name, args, scope));
            case "tenantresourceid" -> NODES.textNode(tenantResourceId(name, args));
            case "extensionresourceid" -> NODES.textNode(extensionResourceId(name, args));
            case "reference" -> reference(name, args, scope);
            case "copyindex" -> copyIndex(name, args, scope);
            case "true" -> booleanValue(name, args, true);
            case "false" -> booleanValue(name, args, false);
            case "null" -> nullValue(name, args);
            case "concat" -> concat(name, args);
            case "format" -> NODES.textNode(format(name, args));
            case "uniquestring" -> NODES.textNode(uniqueString(name, args));
            case "guid" -> NODES.textNode(guid(name, args));
            case "newguid" -> newGuid(name, args);
            case "utcnow" -> utcNow(name, args);
            case "datetimeadd" -> NODES.textNode(dateTimeAdd(name, args));
            case "datetimefromepoch" -> NODES.textNode(ISO_OUTPUT.format(
                Instant.ofEpochSecond(integer(name, args, 0, 1, 1)).atOffset(ZoneOffset.UTC)));
            case "datetimetoepoch" -> number(parseDateTime(string(name, args, 0, 1, 1)).toEpochSecond());
            case "tolower" -> NODES.textNode(lower(string(name, args, 0, 1, 1)));
            case "toupper" -> NODES.textNode(string(name, args, 0, 1, 1).toUpperCase(Locale.ROOT));
            case "string" -> NODES.textNode(text(arg(name, args, 0, 1, 1)));
            case "int" -> number(toInteger(name, arg(name, args, 0, 1, 1)));
            case "float" -> DoubleNode.valueOf(toFloat(name, arg(name, args, 0, 1, 1)));
            case "bool" -> NODES.booleanNode(toBoolean(name, arg(name, args, 0, 1, 1)));
            case "json" -> json(name, string(name, args, 0, 1, 1));
            case "length" -> number(length(name, arg(name, args, 0, 1, 1)));
            case "empty" -> NODES.booleanNode(empty(arg(name, args, 0, 1, 1)));
            case "equals" -> NODES.booleanNode(deepEquals(arg(name, args, 0, 2, 2), args.get(1)));
            case "less" -> NODES.booleanNode(compare(name, args) < 0);
            case "lessorequals" -> NODES.booleanNode(compare(name, args) <= 0);
            case "greater" -> NODES.booleanNode(compare(name, args) > 0);
            case "greaterorequals" -> NODES.booleanNode(compare(name, args) >= 0);
            case "not" -> NODES.booleanNode(!bool(name, args, 0, 1, 1));
            case "coalesce" -> coalesce(name, args);
            case "first" -> first(name, arg(name, args, 0, 1, 1));
            case "last" -> last(name, arg(name, args, 0, 1, 1));
            case "take" -> take(name, args, true);
            case "skip" -> take(name, args, false);
            case "split" -> split(name, args);
            case "replace" -> NODES.textNode(string(name, args, 0, 3, 3)
                .replace(string(name, args, 1, 3, 3), string(name, args, 2, 3, 3)));
            case "substring" -> NODES.textNode(substring(name, args));
            case "trim" -> NODES.textNode(string(name, args, 0, 1, 1).strip());
            case "padleft" -> NODES.textNode(padLeft(name, args));
            case "startswith" -> NODES.booleanNode(
                lower(string(name, args, 0, 2, 2)).startsWith(lower(string(name, args, 1, 2, 2))));
            case "endswith" -> NODES.booleanNode(
                lower(string(name, args, 0, 2, 2)).endsWith(lower(string(name, args, 1, 2, 2))));
            case "contains" -> NODES.booleanNode(contains(name, args));
            case "indexof" -> number(indexOf(name, args, false));
            case "lastindexof" -> number(indexOf(name, args, true));
            case "like" -> NODES.booleanNode(like(string(name, args, 0, 2, 2), string(name, args, 1, 2, 2)));
            case "base64" -> NODES.textNode(Base64.getEncoder().encodeToString(
                string(name, args, 0, 1, 1).getBytes(StandardCharsets.UTF_8)));
            case "base64tostring" -> NODES.textNode(fromBase64(name, string(name, args, 0, 1, 1)));
            case "base64tojson" -> json(name, fromBase64(name, string(name, args, 0, 1, 1)));
            case "datauri" -> NODES.textNode("data:text/plain;charset=utf8;base64," + Base64.getEncoder()
                .encodeToString(text(arg(name, args, 0, 1, 1)).getBytes(StandardCharsets.UTF_8)));
            case "datauritostring" -> NODES.textNode(dataUriToString(name, string(name, args, 0, 1, 1)));
            case "uri" -> NODES.textNode(uri(name, args));
            case "uricomponent" -> NODES.textNode(uriComponent(string(name, args, 0, 1, 1)));
            case "uricomponenttostring" -> NODES.textNode(URLDecoder.decode(
                string(name, args, 0, 1, 1).replace("+", "%2B"), StandardCharsets.UTF_8));
            case "union" -> union(name, args);
            case "intersection" -> intersection(name, args);
            case "createarray" -> createArray(args);
            case "createobject" -> createObject(name, args);
            case "items" -> items(name, args);
            case "objectkeys" -> objectKeys(name, args);
            case "shallowmerge" -> shallowMerge(name, args);
            case "tryget" -> tryGet(name, args);
            case "indexfromend" -> indexFromEnd(name, args, false);
            case "tryindexfromend" -> indexFromEnd(name, args, true);
            case "distinct" -> distinct(array(name, args, 0, 1, 1));
            case "join" -> join(name, args);
            case "array" -> arrayOf(arg(name, args, 0, 1, 1));
            case "add" -> number(Math.addExact(integer(name, args, 0, 2, 2), integer(name, args, 1, 2, 2)));
            case "sub" -> number(Math.subtractExact(integer(name, args, 0, 2, 2), integer(name, args, 1, 2, 2)));
            case "mul" -> number(Math.multiplyExact(integer(name, args, 0, 2, 2), integer(name, args, 1, 2, 2)));
            case "div" -> number(divide(name, args, false));
            case "mod" -> number(divide(name, args, true));
            case "min" -> number(minMax(name, args, true));
            case "max" -> number(minMax(name, args, false));
            case "range" -> range(name, args);
            default -> throw TemplateException.invalid("The template function '" + name
                + "' is not found. Please see https://aka.ms/arm-functions for usage details.");
        };
    }

    // ── Arguments ───────────────────────────────────────────────────────────────

    private static void arity(String name, List<JsonNode> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            String expected = min == max ? String.valueOf(min)
                : max == Integer.MAX_VALUE ? "at least " + min : "between " + min + " and " + max;
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': it expects " + expected + " argument(s) but got " + args.size() + ".");
        }
    }

    private static JsonNode arg(String name, List<JsonNode> args, int index, int min, int max) {
        arity(name, args, min, max);
        return args.get(index);
    }

    private static TemplateException typeError(String name, JsonNode value, String expected) {
        return TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': the argument of type '" + typeName(value) + "' is not valid; expected " + expected + ".");
    }

    private static String string(String name, List<JsonNode> args, int index, int min, int max) {
        JsonNode value = arg(name, args, index, min, max);
        if (!value.isTextual()) {
            throw typeError(name, value, "a string");
        }
        return value.asText();
    }

    private static long integer(String name, List<JsonNode> args, int index, int min, int max) {
        JsonNode value = arg(name, args, index, min, max);
        if (!value.isIntegralNumber()) {
            throw typeError(name, value, "an integer");
        }
        return value.longValue();
    }

    private static boolean bool(String name, List<JsonNode> args, int index, int min, int max) {
        JsonNode value = arg(name, args, index, min, max);
        if (!value.isBoolean()) {
            throw typeError(name, value, "a boolean");
        }
        return value.booleanValue();
    }

    private static ArrayNode array(String name, List<JsonNode> args, int index, int min, int max) {
        JsonNode value = arg(name, args, index, min, max);
        if (!value.isArray()) {
            throw typeError(name, value, "an array");
        }
        return (ArrayNode) value;
    }

    private static ObjectNode object(String name, List<JsonNode> args, int index, int min, int max) {
        JsonNode value = arg(name, args, index, min, max);
        if (!value.isObject()) {
            throw typeError(name, value, "an object");
        }
        return (ObjectNode) value;
    }

    // ── Scope and resources ─────────────────────────────────────────────────────

    private static JsonNode scopeObject(String name, List<JsonNode> args, JsonNode value) {
        if (!args.isEmpty()) {
            throw TemplateException.invalid("The template function '" + name
                + "' with arguments (another scope) is not supported by floci-az.");
        }
        return value;
    }

    private static JsonNode booleanValue(String name, List<JsonNode> args, boolean value) {
        arity(name, args, 0, 0);
        return NODES.booleanNode(value);
    }

    private static JsonNode nullValue(String name, List<JsonNode> args) {
        arity(name, args, 0, 0);
        return NODES.nullNode();
    }

    /**
     * {@code resourceId([subscriptionId], [resourceGroupName], resourceType, name...)}: the type is the first
     * argument that contains a slash; one argument before it is the group, two are the subscription and the group.
     */
    private static String resourceId(String name, List<JsonNode> args, TemplateScope scope) {
        List<String> values = strings(name, args, 2);
        int typeIndex = typeIndex(name, values, 2);
        String subscription = typeIndex == 2 ? values.get(0) : subscriptionId(scope);
        String group = typeIndex == 0 ? text(scope.resourceGroup().path("name")) : values.get(typeIndex - 1);
        return "/subscriptions/" + subscription + "/resourceGroups/" + group
            + providerPath(name, values.get(typeIndex), values.subList(typeIndex + 1, values.size()));
    }

    /** {@code subscriptionResourceId([subscriptionId], resourceType, name...)}. */
    private static String subscriptionResourceId(String name, List<JsonNode> args, TemplateScope scope) {
        List<String> values = strings(name, args, 2);
        int typeIndex = typeIndex(name, values, 1);
        String subscription = typeIndex == 1 ? values.get(0) : subscriptionId(scope);
        return "/subscriptions/" + subscription
            + providerPath(name, values.get(typeIndex), values.subList(typeIndex + 1, values.size()));
    }

    /** {@code tenantResourceId(resourceType, name...)}. */
    private static String tenantResourceId(String name, List<JsonNode> args) {
        List<String> values = strings(name, args, 2);
        int typeIndex = typeIndex(name, values, 0);
        return providerPath(name, values.get(typeIndex), values.subList(typeIndex + 1, values.size()));
    }

    /** {@code extensionResourceId(resourceId, resourceType, name...)}. */
    private static String extensionResourceId(String name, List<JsonNode> args) {
        List<String> values = strings(name, args, 3);
        String base = values.get(0).endsWith("/") ? values.get(0).substring(0, values.get(0).length() - 1)
            : values.get(0);
        if (!values.get(1).contains("/")) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the second argument must be a resource type such as 'Microsoft.Authorization/locks'.");
        }
        return base + providerPath(name, values.get(1), values.subList(2, values.size()));
    }

    private static List<String> strings(String name, List<JsonNode> args, int min) {
        arity(name, args, min, Integer.MAX_VALUE);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            values.add(string(name, args, i, min, Integer.MAX_VALUE));
        }
        return values;
    }

    /** The index of the resource type: the first argument with a slash, at most {@code maxPrefix} in. */
    private static int typeIndex(String name, List<String> values, int maxPrefix) {
        for (int i = 0; i <= Math.min(maxPrefix, values.size() - 1); i++) {
            if (values.get(i).contains("/")) {
                return i;
            }
        }
        throw TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': no resource type such as 'Microsoft.Storage/storageAccounts' was given.");
    }

    /** {@code /providers/{namespace}/{type}/{name}...}, checking that each type segment has its name. */
    private static String providerPath(String name, String type, List<String> names) {
        String[] typeSegments = type.split("/");
        List<String> nameSegments = new ArrayList<>();
        for (String value : names) {
            for (String segment : value.split("/")) {
                nameSegments.add(segment);
            }
        }
        if (typeSegments.length < 2 || nameSegments.size() != typeSegments.length - 1) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the type '" + type + "' requires '" + Math.max(typeSegments.length - 1, 1)
                + "' resource name argument(s).");
        }
        StringBuilder path = new StringBuilder("/providers/").append(typeSegments[0]);
        for (int i = 1; i < typeSegments.length; i++) {
            path.append('/').append(typeSegments[i]).append('/').append(nameSegments.get(i - 1));
        }
        return path.toString();
    }

    private static String subscriptionId(TemplateScope scope) {
        return text(scope.subscription().path("subscriptionId"));
    }

    /** {@code reference(resourceNameOrId, [apiVersion], ['Full'])}. */
    private static JsonNode reference(String name, List<JsonNode> args, TemplateScope scope) {
        String target = string(name, args, 0, 1, 3);
        String apiVersion = args.size() > 1 && !isNull(args.get(1)) ? string(name, args, 1, 1, 3) : null;
        boolean full = args.size() > 2 && "full".equalsIgnoreCase(string(name, args, 2, 1, 3));
        return scope.reference(target, apiVersion, full);
    }

    /**
     * {@code copyIndex([loopName], [offset])} ({@code template-functions-numeric.md}): the iteration of the named
     * loop, or of the resource's own loop, plus {@code offset}.
     */
    private static JsonNode copyIndex(String name, List<JsonNode> args, TemplateScope scope) {
        arity(name, args, 0, 2);
        String loop = null;
        long offset = 0;
        if (!args.isEmpty() && args.getFirst().isTextual()) {
            loop = args.getFirst().asText();
            if (args.size() == 2) {
                offset = integer(name, args, 1, 0, 2);
            }
        } else if (!args.isEmpty()) {
            arity(name, args, 0, 1);
            offset = integer(name, args, 0, 0, 1);
        }
        return number(scope.copyIndex(loop) + offset);
    }

    /** {@code listKeys(resourceNameOrId, apiVersion, [functionValues])} and every other {@code list*} action. */
    private static JsonNode listFunction(String name, List<JsonNode> args, TemplateScope scope) {
        String target = string(name, args, 0, 2, 3);
        String apiVersion = string(name, args, 1, 2, 3);
        JsonNode body = args.size() > 2 ? args.get(2) : null;
        return scope.list(target, name, apiVersion, body);
    }

    // ── Strings ─────────────────────────────────────────────────────────────────

    private static JsonNode concat(String name, List<JsonNode> args) {
        arity(name, args, 1, Integer.MAX_VALUE);
        if (args.stream().allMatch(JsonNode::isArray)) {
            ArrayNode result = NODES.arrayNode();
            args.forEach(value -> result.addAll((ArrayNode) value));
            return result;
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode value : args) {
            if (value.isArray() || value.isObject()) {
                throw typeError(name, value, "strings, integers or booleans, or only arrays");
            }
            result.append(text(value));
        }
        return NODES.textNode(result.toString());
    }

    /**
     * {@code format(formatString, args...)}: .NET composite formatting. Supports {@code {n}}, an alignment
     * {@code {n,width}}, {@code {{} and {@code }}}, and the numeric format strings {@code N}, {@code D} and
     * {@code X}; any other format string is an error rather than a guess.
     */
    private static String format(String name, List<JsonNode> args) {
        String pattern = string(name, args, 0, 1, Integer.MAX_VALUE);
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '{') {
                result.append('{');
                i += 2;
            } else if (c == '}' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '}') {
                result.append('}');
                i += 2;
            } else if (c == '{') {
                int close = pattern.indexOf('}', i);
                if (close < 0) {
                    throw TemplateException.invalid("Unable to evaluate template language function '" + name
                        + "': the format string '" + pattern + "' is not valid.");
                }
                result.append(formatItem(name, pattern.substring(i + 1, close), args));
                i = close + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private static String formatItem(String name, String item, List<JsonNode> args) {
        String spec = null;
        int colon = item.indexOf(':');
        if (colon >= 0) {
            spec = item.substring(colon + 1);
            item = item.substring(0, colon);
        }
        int alignment = 0;
        int comma = item.indexOf(',');
        if (comma >= 0) {
            alignment = Integer.parseInt(item.substring(comma + 1).strip());
            item = item.substring(0, comma);
        }
        int index;
        try {
            index = Integer.parseInt(item.strip());
        } catch (NumberFormatException e) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': '{" + item + "}' is not a valid format item.");
        }
        if (index + 1 >= args.size()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the format item '{" + index + "}' has no argument.");
        }
        String formatted = formatValue(name, args.get(index + 1), spec);
        if (alignment > 0) {
            return " ".repeat(Math.max(0, alignment - formatted.length())) + formatted;
        }
        if (alignment < 0) {
            return formatted + " ".repeat(Math.max(0, -alignment - formatted.length()));
        }
        return formatted;
    }

    private static String formatValue(String name, JsonNode value, String spec) {
        if (spec == null || spec.isEmpty()) {
            return text(value);
        }
        char kind = Character.toUpperCase(spec.charAt(0));
        String digits = spec.substring(1);
        if (!digits.chars().allMatch(Character::isDigit) || !value.isNumber()) {
            throw unsupportedFormat(name, spec);
        }
        Integer precision = digits.isEmpty() ? null : Integer.parseInt(digits);
        return switch (kind) {
            case 'N' -> String.format(Locale.ROOT, "%,." + (precision == null ? 2 : precision) + "f",
                value.doubleValue());
            case 'D' -> {
                String plain = Long.toString(Math.abs(value.longValue()));
                String padded = "0".repeat(Math.max(0, (precision == null ? 0 : precision) - plain.length())) + plain;
                yield value.longValue() < 0 ? "-" + padded : padded;
            }
            case 'X' -> {
                String hex = Long.toHexString(value.longValue());
                hex = Character.isUpperCase(spec.charAt(0)) ? hex.toUpperCase(Locale.ROOT) : hex;
                yield "0".repeat(Math.max(0, (precision == null ? 0 : precision) - hex.length())) + hex;
            }
            default -> throw unsupportedFormat(name, spec);
        };
    }

    private static TemplateException unsupportedFormat(String name, String spec) {
        return TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': the format string '" + spec + "' is not supported by floci-az.");
    }

    /**
     * {@code uniqueString(args...)}: a deterministic 13-character lowercase base32 hash of the arguments joined
     * with {@code -}. The length and alphabet are Azure's; the hash is not, so floci-az's value differs from the one
     * Azure computes for the same arguments.
     */
    private static String uniqueString(String name, List<JsonNode> args) {
        byte[] digest = digest("SHA-256", String.join("-", strings(name, args, 1)).getBytes(StandardCharsets.UTF_8));
        long bits = ByteBuffer.wrap(digest).getLong();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            result.append(BASE32.charAt((int) ((bits >>> (59 - 5 * i)) & 31)));
        }
        result.append(BASE32.charAt(((digest[8] & 0xff) >>> 3) & 31));
        return result.toString();
    }

    /**
     * {@code guid(args...)}: an RFC 4122 version 5 UUID over the arguments joined with {@code -}, in the documented
     * namespace {@code 11fb06fb-712d-4ddd-98c7-e71bbd588830}. Azure documents "some modifications" to that
     * algorithm without naming them, so floci-az's value may differ from Azure's for the same arguments.
     */
    private static String guid(String name, List<JsonNode> args) {
        byte[] nameBytes = String.join("-", strings(name, args, 1)).getBytes(StandardCharsets.UTF_8);
        ByteBuffer material = ByteBuffer.allocate(16 + nameBytes.length);
        material.putLong(GUID_NAMESPACE.getMostSignificantBits()).putLong(GUID_NAMESPACE.getLeastSignificantBits())
            .put(nameBytes);
        byte[] hash = digest("SHA-1", material.array());
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static JsonNode newGuid(String name, List<JsonNode> args) {
        arity(name, args, 0, 0);
        return NODES.textNode(UUID.randomUUID().toString());
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    private static String substring(String name, List<JsonNode> args) {
        String value = string(name, args, 0, 2, 3);
        long start = integer(name, args, 1, 2, 3);
        long length = args.size() > 2 ? integer(name, args, 2, 2, 3) : value.length() - start;
        if (start < 0 || length < 0 || start + length > value.length()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the index and length parameters must refer to a location within the string.");
        }
        return value.substring((int) start, (int) (start + length));
    }

    private static String padLeft(String name, List<JsonNode> args) {
        JsonNode value = arg(name, args, 0, 2, 3);
        if (!value.isTextual() && !value.isIntegralNumber()) {
            throw typeError(name, value, "a string or an integer");
        }
        long total = integer(name, args, 1, 2, 3);
        String padding = args.size() > 2 ? string(name, args, 2, 2, 3) : " ";
        if (padding.length() != 1) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the padding character must be a single character.");
        }
        String text = text(value);
        return padding.repeat((int) Math.max(0, total - text.length())) + text;
    }

    private static JsonNode split(String name, List<JsonNode> args) {
        String value = string(name, args, 0, 2, 2);
        JsonNode delimiters = args.get(1);
        List<String> separators = new ArrayList<>();
        if (delimiters.isTextual()) {
            separators.add(delimiters.asText());
        } else if (delimiters.isArray()) {
            delimiters.forEach(delimiter -> separators.add(text(delimiter)));
        } else {
            throw typeError(name, delimiters, "a string or an array of strings");
        }
        String regex = separators.stream().filter(separator -> !separator.isEmpty()).map(Pattern::quote)
            .reduce((a, b) -> a + "|" + b).orElse(null);
        ArrayNode result = NODES.arrayNode();
        if (regex == null) {
            result.add(value);
            return result;
        }
        for (String part : value.split(regex, -1)) {
            result.add(part);
        }
        return result;
    }

    /** {@code contains}: a substring case-sensitively, an array element by value, an object key case-insensitively. */
    private static boolean contains(String name, List<JsonNode> args) {
        JsonNode container = arg(name, args, 0, 2, 2);
        JsonNode item = args.get(1);
        if (container.isTextual()) {
            return container.asText().contains(text(item));
        }
        if (container.isArray()) {
            for (JsonNode element : container) {
                if (deepEquals(element, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container.isObject()) {
            return property(container, text(item)) != null;
        }
        throw typeError(name, container, "a string, an array or an object");
    }

    /**
     * {@code indexOf} and {@code lastIndexOf}: case-insensitive within a string ({@code template-functions-string.md}),
     * case-sensitive by value within an array ({@code template-functions-array.md}); -1 when not found.
     */
    private static long indexOf(String name, List<JsonNode> args, boolean last) {
        JsonNode container = arg(name, args, 0, 2, 2);
        JsonNode item = args.get(1);
        if (container.isTextual()) {
            String haystack = lower(container.asText());
            String needle = lower(text(item));
            return last ? haystack.lastIndexOf(needle) : haystack.indexOf(needle);
        }
        if (container.isArray()) {
            long found = -1;
            for (int i = 0; i < container.size(); i++) {
                if (deepEquals(container.get(i), item)) {
                    found = i;
                    if (!last) {
                        return found;
                    }
                }
            }
            return found;
        }
        throw typeError(name, container, "a string or an array");
    }

    /** {@code like(value, pattern)}: {@code *} and {@code ?} wildcards, matched case-insensitively. */
    private static boolean like(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(value).matches();
    }

    private static String fromBase64(String name, String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the value is not valid base64.");
        }
    }

    private static String dataUriToString(String name, String value) {
        int comma = value.indexOf(',');
        if (!value.startsWith("data:") || comma < 0) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the value is not a data URI.");
        }
        String header = value.substring(5, comma);
        String data = value.substring(comma + 1);
        return header.endsWith(";base64") ? fromBase64(name, data) : URLDecoder.decode(data, StandardCharsets.UTF_8);
    }

    private static String uri(String name, List<JsonNode> args) {
        String base = string(name, args, 0, 2, 2);
        String relative = string(name, args, 1, 2, 2);
        try {
            return URI.create(base).resolve(relative).toString();
        } catch (IllegalArgumentException e) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': '" + base + "' and '" + relative + "' do not form a URI.");
        }
    }

    /** {@code uriComponent}: percent-encodes everything but the RFC 3986 unreserved characters, as .NET does. */
    private static String uriComponent(String value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                result.append(c);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
        }
        return result.toString();
    }

    // ── Conversions and comparisons ─────────────────────────────────────────────

    private static long toInteger(String name, JsonNode value) {
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return (long) value.doubleValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().strip());
            } catch (NumberFormatException e) {
                throw TemplateException.invalid("Unable to evaluate template language function '" + name
                    + "': '" + value.asText() + "' is not an integer.");
            }
        }
        throw typeError(name, value, "a string or a number");
    }

    private static double toFloat(String name, JsonNode value) {
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.asText().strip());
            } catch (NumberFormatException e) {
                throw TemplateException.invalid("Unable to evaluate template language function '" + name
                    + "': '" + value.asText() + "' is not a number.");
            }
        }
        throw typeError(name, value, "a string or a number");
    }

    private static boolean toBoolean(String name, JsonNode value) {
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue() != 0;
        }
        if (value.isTextual() && ("true".equalsIgnoreCase(value.asText()) || "false".equalsIgnoreCase(value.asText()))) {
            return Boolean.parseBoolean(lower(value.asText()));
        }
        throw TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': '" + text(value) + "' is not a boolean.");
    }

    private static JsonNode json(String name, String value) {
        try {
            return TemplateValues.MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the value is not valid JSON.");
        }
    }

    private static long length(String name, JsonNode value) {
        if (value.isTextual()) {
            return value.asText().length();
        }
        if (value.isArray() || value.isObject()) {
            return value.size();
        }
        throw typeError(name, value, "a string, an array or an object");
    }

    private static boolean empty(JsonNode value) {
        if (isNull(value)) {
            return true;
        }
        if (value.isTextual()) {
            return value.asText().isEmpty();
        }
        return (value.isArray() || value.isObject()) && value.isEmpty();
    }

    /** {@code less} and friends: two integers by value, or two strings ordinally. */
    private static int compare(String name, List<JsonNode> args) {
        JsonNode a = arg(name, args, 0, 2, 2);
        JsonNode b = args.get(1);
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (a.isTextual() && b.isTextual()) {
            return a.asText().compareTo(b.asText());
        }
        throw TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': the arguments must both be integers or both be strings.");
    }

    private static JsonNode coalesce(String name, List<JsonNode> args) {
        arity(name, args, 1, Integer.MAX_VALUE);
        for (JsonNode value : args) {
            if (!isNull(value)) {
                return value;
            }
        }
        return NODES.nullNode();
    }

    // ── Arrays and objects ──────────────────────────────────────────────────────

    private static JsonNode first(String name, JsonNode value) {
        if (value.isTextual()) {
            return NODES.textNode(value.asText().isEmpty() ? "" : value.asText().substring(0, 1));
        }
        if (value.isArray()) {
            return value.isEmpty() ? NODES.nullNode() : value.get(0);
        }
        throw typeError(name, value, "a string or an array");
    }

    private static JsonNode last(String name, JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText();
            return NODES.textNode(text.isEmpty() ? "" : text.substring(text.length() - 1));
        }
        if (value.isArray()) {
            return value.isEmpty() ? NODES.nullNode() : value.get(value.size() - 1);
        }
        throw typeError(name, value, "a string or an array");
    }

    private static JsonNode take(String name, List<JsonNode> args, boolean take) {
        JsonNode value = arg(name, args, 0, 2, 2);
        long count = integer(name, args, 1, 2, 2);
        int size = value.isTextual() ? value.asText().length() : value.size();
        int cut = (int) Math.max(0, Math.min(size, count));
        int from = take ? 0 : cut;
        int to = take ? cut : size;
        if (value.isTextual()) {
            return NODES.textNode(value.asText().substring(from, to));
        }
        if (value.isArray()) {
            ArrayNode result = NODES.arrayNode();
            for (int i = from; i < to; i++) {
                result.add(value.get(i));
            }
            return result;
        }
        throw typeError(name, value, "a string or an array");
    }

    /**
     * {@code union}: arrays keep each value once in order of first appearance; objects merge, later values
     * overwriting earlier ones and nested objects merging deeply ({@code template-functions-object.md}, union).
     */
    private static JsonNode union(String name, List<JsonNode> args) {
        arity(name, args, 1, Integer.MAX_VALUE);
        if (args.stream().allMatch(JsonNode::isArray)) {
            ArrayNode all = NODES.arrayNode();
            args.forEach(value -> all.addAll((ArrayNode) value));
            return distinct(all);
        }
        if (args.stream().allMatch(JsonNode::isObject)) {
            ObjectNode result = NODES.objectNode();
            for (JsonNode value : args) {
                deepMerge(result, (ObjectNode) value);
            }
            return result;
        }
        throw TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': the arguments must all be arrays or all be objects.");
    }

    private static void deepMerge(ObjectNode target, ObjectNode source) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = target.get(field.getKey());
            if (existing instanceof ObjectNode existingObject && field.getValue() instanceof ObjectNode incoming) {
                deepMerge(existingObject, incoming);
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }

    private static JsonNode intersection(String name, List<JsonNode> args) {
        arity(name, args, 2, Integer.MAX_VALUE);
        if (args.stream().allMatch(JsonNode::isArray)) {
            ArrayNode result = NODES.arrayNode();
            for (JsonNode element : distinct((ArrayNode) args.getFirst())) {
                boolean inAll = args.stream().skip(1).allMatch(other -> {
                    for (JsonNode candidate : other) {
                        if (deepEquals(candidate, element)) {
                            return true;
                        }
                    }
                    return false;
                });
                if (inAll) {
                    result.add(element);
                }
            }
            return result;
        }
        if (args.stream().allMatch(JsonNode::isObject)) {
            ObjectNode result = NODES.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = args.getFirst().fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                boolean inAll = args.stream().skip(1).allMatch(other -> {
                    JsonNode value = property(other, field.getKey());
                    return value != null && deepEquals(value, field.getValue());
                });
                if (inAll) {
                    result.set(field.getKey(), field.getValue());
                }
            }
            return result;
        }
        throw TemplateException.invalid("Unable to evaluate template language function '" + name
            + "': the arguments must all be arrays or all be objects.");
    }

    private static ArrayNode distinct(ArrayNode values) {
        ArrayNode result = NODES.arrayNode();
        for (JsonNode value : values) {
            boolean seen = false;
            for (JsonNode kept : result) {
                if (deepEquals(kept, value)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result.add(value);
            }
        }
        return result;
    }

    private static JsonNode createArray(List<JsonNode> args) {
        ArrayNode result = NODES.arrayNode();
        args.forEach(result::add);
        return result;
    }

    private static JsonNode createObject(String name, List<JsonNode> args) {
        if (args.size() % 2 != 0) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': it expects an even number of arguments, a key and a value each.");
        }
        ObjectNode result = NODES.objectNode();
        for (int i = 0; i < args.size(); i += 2) {
            result.set(string(name, args, i, 0, Integer.MAX_VALUE), args.get(i + 1));
        }
        return result;
    }

    /**
     * {@code items(object)}: an array of {@code {key, value}} objects, sorted by key; the documented example lists
     * {@code item001} before {@code item002} although the object declares them the other way round.
     */
    private static JsonNode items(String name, List<JsonNode> args) {
        ObjectNode value = object(name, args, 0, 1, 1);
        List<String> keys = new ArrayList<>();
        value.fieldNames().forEachRemaining(keys::add);
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        ArrayNode result = NODES.arrayNode();
        for (String key : keys) {
            ObjectNode entry = NODES.objectNode();
            entry.put("key", key);
            entry.set("value", value.get(key));
            result.add(entry);
        }
        return result;
    }

    private static JsonNode objectKeys(String name, List<JsonNode> args) {
        ArrayNode result = NODES.arrayNode();
        object(name, args, 0, 1, 1).fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static JsonNode shallowMerge(String name, List<JsonNode> args) {
        ObjectNode result = NODES.objectNode();
        for (JsonNode value : array(name, args, 0, 1, 1)) {
            if (!value.isObject()) {
                throw typeError(name, value, "an array of objects");
            }
            result.setAll((ObjectNode) value);
        }
        return result;
    }

    /** {@code tryGet(itemToTest, keyOrIndex)}: the value, or null when there is none. */
    private static JsonNode tryGet(String name, List<JsonNode> args) {
        JsonNode container = arg(name, args, 0, 2, 2);
        JsonNode key = args.get(1);
        JsonNode found = null;
        if (container.isObject() && key.isTextual()) {
            found = property(container, key.asText());
        } else if (container.isArray() && key.isIntegralNumber() && key.longValue() >= 0
                && key.longValue() < container.size()) {
            found = container.get((int) key.longValue());
        }
        return found == null ? NODES.nullNode() : found;
    }

    /** {@code indexFromEnd(array, n)}: the n-th element from the end, 1-based. */
    private static JsonNode indexFromEnd(String name, List<JsonNode> args, boolean tolerant) {
        ArrayNode values = array(name, args, 0, 2, 2);
        long fromEnd = integer(name, args, 1, 2, 2);
        long index = values.size() - fromEnd;
        if (fromEnd < 1 || index < 0) {
            if (tolerant) {
                return NODES.nullNode();
            }
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the index '" + fromEnd + "' is out of bounds.");
        }
        return values.get((int) index);
    }

    private static JsonNode join(String name, List<JsonNode> args) {
        ArrayNode values = array(name, args, 0, 2, 2);
        String delimiter = string(name, args, 1, 2, 2);
        List<String> parts = new ArrayList<>();
        for (JsonNode value : values) {
            if (value.isContainerNode()) {
                throw typeError(name, value, "an array of strings, integers or booleans");
            }
            parts.add(text(value));
        }
        return NODES.textNode(String.join(delimiter, parts));
    }

    private static JsonNode arrayOf(JsonNode value) {
        if (value.isArray()) {
            return value;
        }
        ArrayNode result = NODES.arrayNode();
        result.add(value);
        return result;
    }

    // ── Numbers ─────────────────────────────────────────────────────────────────

    private static long divide(String name, List<JsonNode> args, boolean remainder) {
        long dividend = integer(name, args, 0, 2, 2);
        long divisor = integer(name, args, 1, 2, 2);
        if (divisor == 0) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the divisor must not be zero.");
        }
        return remainder ? dividend % divisor : dividend / divisor;
    }

    /** {@code min} and {@code max}: over integer arguments, or the integers of one array argument. */
    private static long minMax(String name, List<JsonNode> args, boolean min) {
        arity(name, args, 1, Integer.MAX_VALUE);
        List<JsonNode> values = new ArrayList<>();
        if (args.size() == 1 && args.getFirst().isArray()) {
            args.getFirst().forEach(values::add);
        } else {
            values.addAll(args);
        }
        if (values.isEmpty()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': it needs at least one integer.");
        }
        long result = min ? Long.MAX_VALUE : Long.MIN_VALUE;
        for (JsonNode value : values) {
            if (!value.isIntegralNumber()) {
                throw typeError(name, value, "integers");
            }
            result = min ? Math.min(result, value.longValue()) : Math.max(result, value.longValue());
        }
        return result;
    }

    /** {@code range(startIndex, count)}: count is between 0 and 10000 ({@code template-functions-array.md}). */
    private static JsonNode range(String name, List<JsonNode> args) {
        long start = integer(name, args, 0, 2, 2);
        long count = integer(name, args, 1, 2, 2);
        if (count < 0 || count > 10000 || start + count > Integer.MAX_VALUE) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': the count must be between 0 and 10000 and the range must end within an integer.");
        }
        ArrayNode result = NODES.arrayNode();
        for (long i = 0; i < count; i++) {
            result.add(number(start + i));
        }
        return result;
    }

    // ── Dates ───────────────────────────────────────────────────────────────────

    private static JsonNode utcNow(String name, List<JsonNode> args) {
        String format = args.isEmpty() ? null : string(name, args, 0, 0, 1);
        return NODES.textNode(formatDateTime(name, OffsetDateTime.now(ZoneOffset.UTC), format));
    }

    /**
     * {@code dateTimeAdd(base, duration, [format])}. A year is 365 days and a month 30, as the documented examples
     * show ({@code template-functions-date.md}: P1Y from 2024-01-01 is 2024-12-31, P1M from 2023-02-01 is
     * 2023-03-03). Without a format the result is ISO 8601 ({@code yyyy-MM-ddTHH:mm:ssZ}).
     */
    private static String dateTimeAdd(String name, List<JsonNode> args) {
        OffsetDateTime base = parseDateTime(string(name, args, 0, 2, 3));
        String duration = string(name, args, 1, 2, 3);
        String format = args.size() > 2 ? string(name, args, 2, 2, 3) : null;
        Matcher matcher = DURATION.matcher(duration);
        if (!matcher.matches() || duration.endsWith("P") || duration.endsWith("T")) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + name
                + "': '" + duration + "' is not an ISO 8601 duration.");
        }
        long days = group(matcher, 2) * 365 + group(matcher, 3) * 30 + group(matcher, 4) * 7 + group(matcher, 5);
        double seconds = matcher.group(8) == null ? 0 : Double.parseDouble(matcher.group(8));
        Duration amount = Duration.ofDays(days).plusHours(group(matcher, 6)).plusMinutes(group(matcher, 7))
            .plusMillis(Math.round(seconds * 1000));
        OffsetDateTime result = matcher.group(1) != null ? base.minus(amount) : base.plus(amount);
        return format == null ? ISO_OUTPUT.format(result.withOffsetSameInstant(ZoneOffset.UTC))
            : formatDateTime(name, result, format);
    }

    private static long group(Matcher matcher, int index) {
        return matcher.group(index) == null ? 0 : Long.parseLong(matcher.group(index));
    }

    /** Parses the ISO 8601 forms templates use: {@code 2020-04-07 14:53:14Z}, {@code 2020-04-07T14:53:14Z}, {@code 20190305T175318Z}, a date. */
    static OffsetDateTime parseDateTime(String value) {
        String normalized = value.strip().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // Not offset ISO; the forms below cover the rest.
        }
        try {
            return LocalDateTime.parse(normalized.endsWith("Z") ? normalized.substring(0, normalized.length() - 1)
                : normalized).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Not local ISO either.
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                .atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Not the compact utcNow() form.
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw TemplateException.invalid("The value '" + value + "' is not an ISO 8601 date and time.");
        }
    }

    /**
     * Formats with a .NET standard ({@code u}, {@code d}, {@code s}, {@code o}) or custom ({@code yyyy}, {@code MM},
     * {@code d}, {@code HH}, {@code mm}, {@code ss}, {@code f}..., quoted literals) date format string; null is the
     * {@code utcNow()} default {@code yyyyMMddTHHmmssZ}.
     */
    static String formatDateTime(String name, OffsetDateTime value, String format) {
        OffsetDateTime utc = value.withOffsetSameInstant(ZoneOffset.UTC);
        if (format == null || format.isEmpty()) {
            return DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(utc);
        }
        if (format.length() == 1) {
            return switch (format) {
                case "u" -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").format(utc);
                case "d" -> DateTimeFormatter.ofPattern("MM/dd/yyyy").format(utc);
                case "s" -> DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").format(utc);
                case "o", "O" -> new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 7, 7, true).appendLiteral('Z').toFormatter()
                    .format(utc);
                default -> throw TemplateException.invalid("Unable to evaluate template language function '" + name
                    + "': the date format '" + format + "' is not supported by floci-az.");
            };
        }
        return DateTimeFormatter.ofPattern(customPattern(name, format), Locale.ROOT).format(utc);
    }

    /** Translates a .NET custom date format string to a {@link DateTimeFormatter} pattern. */
    private static String customPattern(String name, String format) {
        StringBuilder pattern = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            int run = 1;
            while (i + run < format.length() && format.charAt(i + run) == c) {
                run++;
            }
            switch (c) {
                case 'y', 'M', 'd', 'H', 'h', 'm', 's' -> {
                    if (c == 'd' && run > 2) {
                        throw TemplateException.invalid("Unable to evaluate template language function '" + name
                            + "': day names in a date format are not supported by floci-az.");
                    }
                    pattern.append(String.valueOf(c).repeat(run));
                }
                case 'f', 'F' -> pattern.append("S".repeat(run));
                case 't' -> pattern.append('a');
                case '\'', '"' -> {
                    int close = format.indexOf(c, i + 1);
                    String literal = format.substring(i + 1, close < 0 ? format.length() : close);
                    pattern.append('\'').append(literal.replace("'", "''")).append('\'');
                    run = (close < 0 ? format.length() : close + 1) - i;
                }
                case '\\' -> {
                    if (i + 1 < format.length()) {
                        pattern.append('\'').append(format.charAt(i + 1)).append('\'');
                    }
                    run = 2;
                }
                default -> {
                    String literal = String.valueOf(c).repeat(run);
                    pattern.append(Character.isLetter(c) ? "'" + literal + "'" : literal.replace("'", "''"));
                }
            }
            i += run;
        }
        return pattern.toString();
    }
}
