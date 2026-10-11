package io.floci.az.services.blob;

import io.floci.az.core.XmlBuilder;
import io.floci.az.core.XmlParser;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Blob index tags: the tag set codec and its validation.
 *
 * <p>Tags live in the blob's stored metadata under {@link #METADATA_KEY}, encoded the way the
 * {@code x-ms-tags} header carries them ({@code k1=v1&k2=v2}, URL-encoded), so they persist through
 * every storage backend with the rest of the blob and keep their order.
 *
 * <p>Limits and error codes follow the Set Blob Tags contract (at most 10 tags, keys of 1 to 128
 * characters, values of up to 256, letters, digits, space and {@code + - . / : = _}) as Azurite
 * enforces it in {@code validateBlobTag}.
 */
final class BlobTags {

    static final String METADATA_KEY = "BlobTags";
    static final String HEADER = "x-ms-tags";

    private static final int MAX_TAGS = 10;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 256;

    private BlobTags() {
    }

    /** The tags stored with a blob, in the order they were set; empty when it has none. */
    static Map<String, String> of(Map<String, String> metadata) {
        String encoded = metadata.get(METADATA_KEY);
        Map<String, String> tags = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return tags;
        }
        for (String pair : encoded.split("&")) {
            int separator = pair.indexOf('=');
            tags.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return tags;
    }

    /** Replaces the tags held in {@code metadata}; an empty set removes them. */
    static void store(Map<String, String> metadata, Map<String, String> tags) {
        if (tags.isEmpty()) {
            metadata.remove(METADATA_KEY);
            return;
        }
        List<String> pairs = new ArrayList<>(tags.size());
        tags.forEach((key, value) -> pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        metadata.put(METADATA_KEY, String.join("&", pairs));
    }

    /**
     * Parses the {@code x-ms-tags} header of Put Blob and Put Block List: URL-encoded
     * {@code key=value} pairs joined by {@code &}. A {@code +} decodes to a space, as the SDKs encode
     * with form encoding.
     */
    static Map<String, String> fromHeader(String header) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (header == null || header.isEmpty()) {
            return new LinkedHashMap<>();
        }
        for (String pair : header.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                throw BlobTagException.invalidHeader(HEADER, header);
            }
            try {
                pairs.add(Map.entry(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                throw BlobTagException.invalidHeader(HEADER, header);
            }
        }
        return validated(pairs);
    }

    /**
     * Parses a Set Blob Tags body: {@code <Tags><TagSet><Tag><Key/><Value/></Tag>...}.
     *
     * <p>The {@code Tags} root and its {@code TagSet} are required, so a missing or unrelated body
     * is rejected instead of being read as "no tags" and wiping the blob's tag set. Clearing the
     * tags takes an explicit {@code <Tags><TagSet/></Tags>}.
     */
    static Map<String, String> fromXml(String body) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (body == null || body.isBlank()) {
            throw invalidDocument();
        }
        boolean tagSetSeen = false;
        try {
            XMLStreamReader reader = XmlParser.newStreamReader(body);
            String key = null;
            String value = null;
            boolean inTag = false;
            int depth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = reader.getLocalName();
                    if (depth == 1 && !"Tags".equals(name)) {
                        throw invalidDocument();
                    }
                    if (depth == 2 && "TagSet".equals(name)) {
                        tagSetSeen = true;
                    } else if ("Tag".equals(name)) {
                        inTag = true;
                        key = null;
                        value = null;
                    } else if (inTag && "Key".equals(name)) {
                        key = reader.getElementText();
                        depth--;
                    } else if (inTag && "Value".equals(name)) {
                        value = reader.getElementText();
                        depth--;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    if ("Tag".equals(reader.getLocalName())) {
                        inTag = false;
                        pairs.add(Map.entry(key == null ? "" : key, value == null ? "" : value));
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw invalidDocument();
        }
        if (!tagSetSeen) {
            throw invalidDocument();
        }
        return validated(pairs);
    }

    private static BlobTagException invalidDocument() {
        return new BlobTagException(400, "InvalidXmlDocument", "XML specified is not syntactically valid.");
    }

    static String toXml(Map<String, String> tags) {
        XmlBuilder xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .start("Tags");
        appendTagSet(xml, tags);
        return xml.end("Tags").build();
    }

    static void appendTagSet(XmlBuilder xml, Map<String, String> tags) {
        xml.start("TagSet");
        tags.forEach((key, value) -> xml.start("Tag")
                .elem("Key", key)
                .elem("Value", value)
                .end("Tag"));
        xml.end("TagSet");
    }

    private static Map<String, String> validated(List<Map.Entry<String, String>> pairs) {
        if (pairs.size() > MAX_TAGS) {
            throw tagsTooLarge();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : pairs) {
            String key = pair.getKey();
            String value = pair.getValue();
            if (key.isEmpty()) {
                throw new BlobTagException(400, "EmptyTagName",
                        "The name of one of the tag key-value pairs is empty.");
            }
            if (key.length() > MAX_KEY_LENGTH || value.length() > MAX_VALUE_LENGTH) {
                throw tagsTooLarge();
            }
            if (!isValidTagText(key) || !isValidTagText(value)) {
                throw new BlobTagException(400, "InvalidTag",
                        "The tags specified are invalid. It contains characters that are not permitted.");
            }
            if (tags.putIfAbsent(key, value) != null) {
                throw new BlobTagException(400, "DuplicateTagNames", "The tags specified contain duplicate names.");
            }
        }
        return tags;
    }

    private static BlobTagException tagsTooLarge() {
        return new BlobTagException(400, "TagsTooLarge", "The tags specified exceed the maximum permissible limit.");
    }

    /** Letters, digits, space and {@code + - . / : = _}: the characters a tag key or value may use. */
    static boolean isValidTagText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ' ' || c == '+' || c == '-' || c == '.' || c == '/' || c == ':' || c == '='
                    || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
