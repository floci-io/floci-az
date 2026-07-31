package io.floci.az.core;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight StAX-based helpers for parsing XML request bodies.
 *
 * <p>Uses {@code javax.xml.stream} (part of the JDK — no extra dependency).
 * Namespace prefixes are ignored so that both plain {@code <Key>} and
 * namespace-qualified elements match by local name.
 * All methods silently return empty collections on malformed input.
 */
public final class XmlParser {

    private static final XMLInputFactory FACTORY;

    static {
        FACTORY = XMLInputFactory.newInstance();
        FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private XmlParser() {}

    /**
     * Creates a stream reader with this class's hardened settings (namespace-aware,
     * external entities and DTDs disabled) — for callers whose parsing needs go beyond
     * the extraction helpers here (e.g. reading attributes).
     */
    public static XMLStreamReader newStreamReader(String xml) throws XMLStreamException {
        return FACTORY.createXMLStreamReader(new StringReader(xml));
    }

    /**
     * Reads the text content of the current element if it is a leaf (contains only text).
     * If the element contains nested child elements, the subtree is skipped and {@code null} is returned.
     * After this returns, the reader is positioned on the END_ELEMENT of the element that was open.
     */
    public static String readLeafText(XMLStreamReader r) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(r.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                int depth = 2;
                while (r.hasNext()) {
                    int e = r.next();
                    if (e == XMLStreamConstants.START_ELEMENT) depth++;
                    else if (e == XMLStreamConstants.END_ELEMENT) {
                        if (--depth == 0) break;
                    }
                }
                return null;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the text content of every element whose local name matches {@code elementName}.
     */
    public static List<String> extractAll(String xml, String elementName) {
        List<String> result = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return result;
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && elementName.equals(r.getLocalName())) {
                    result.add(r.getElementText());
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Extracts the text content of the first element matching {@code elementName},
     * or {@code defaultValue} if no such element exists.
     */
    public static String extractFirst(String xml, String elementName, String defaultValue) {
        List<String> all = extractAll(xml, elementName);
        return all.isEmpty() ? defaultValue : all.get(0);
    }

    /**
     * Returns {@code true} if the document contains at least one element with the given
     * local name whose text equals {@code value} (case-sensitive).
     */
    public static boolean containsValue(String xml, String elementName, String value) {
        return extractAll(xml, elementName).stream().anyMatch(value::equals);
    }

    /**
     * Extracts sibling key/value pairs from every {@code parentElement} block.
     * Insertion order is preserved.
     *
     * <pre>{@code
     * Map<String,String> tags = XmlParser.extractPairs(body, "Tag", "Key", "Value");
     * }</pre>
     */
    public static Map<String, String> extractPairs(String xml, String parentElement,
                                                    String keyElement, String valueElement) {
        Map<String, String> result = new LinkedHashMap<>();
        if (xml == null || xml.isEmpty()) return result;
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            String pendingKey = null;
            boolean inParent = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (parentElement.equals(local)) {
                        inParent = true;
                        pendingKey = null;
                    } else if (inParent && keyElement.equals(local)) {
                        pendingKey = r.getElementText();
                    } else if (inParent && valueElement.equals(local) && pendingKey != null) {
                        result.put(pendingKey, r.getElementText());
                        pendingKey = null;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (parentElement.equals(r.getLocalName())) inParent = false;
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Extracts every group of elements nested inside a repeating {@code parentElement},
     * returning each group as a {@code Map<localName, List<text>>}.
     * Allows for repeated child elements with the same name.
     */
    public static List<Map<String, List<String>>> extractGroupsMulti(String xml, String parentElement) {
        List<Map<String, List<String>>> result = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return result;
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            Map<String, List<String>> current = null;
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (parentElement.equals(local)) {
                        current = new LinkedHashMap<>();
                        depth = 1;
                    } else if (current != null && depth == 1) {
                        String text = readLeafText(r);
                        if (text != null) {
                            current.computeIfAbsent(local, k -> new ArrayList<>()).add(text);
                        }
                    } else if (current != null) {
                        depth++;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (current != null && parentElement.equals(r.getLocalName())) {
                        result.add(current);
                        current = null;
                        depth = 0;
                    } else if (current != null) {
                        depth--;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Extracts every group of elements nested inside a repeating {@code parentElement},
     * returning each group as a {@code Map<localName, text>}.
     */
    public static List<Map<String, String>> extractGroups(String xml, String parentElement) {
        List<Map<String, String>> result = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return result;
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            Map<String, String> current = null;
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (parentElement.equals(local)) {
                        current = new LinkedHashMap<>();
                        depth = 1;
                    } else if (current != null && depth == 1) {
                        String text = readLeafText(r);
                        if (text != null) {
                            current.put(local, text);
                        }
                    } else if (current != null) {
                        depth++;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (current != null && parentElement.equals(r.getLocalName())) {
                        result.add(current);
                        current = null;
                        depth = 0;
                    } else if (current != null) {
                        depth--;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Extracts key/value pairs from a repeating {@code pairElement} nested at any depth
     * inside each {@code parentElement} group, returning one map per group.
     *
     * <pre>{@code
     * List<Map<String,String>> filters = XmlParser.extractPairsPerGroup(
     *         body, "Configuration", "Rule", "Name", "Value");
     * }</pre>
     */
    public static List<Map<String, String>> extractPairsPerGroup(
            String xml, String parentElement,
            String pairElement, String keyElement, String valueElement) {
        List<Map<String, String>> result = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return result;
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            Map<String, String> current = null;
            boolean inPair = false;
            String pendingKey = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (parentElement.equals(local)) {
                        current = new LinkedHashMap<>();
                    } else if (current != null && pairElement.equals(local)) {
                        inPair = true;
                        pendingKey = null;
                    } else if (inPair && keyElement.equals(local)) {
                        pendingKey = r.getElementText();
                    } else if (inPair && valueElement.equals(local) && pendingKey != null) {
                        current.put(pendingKey, r.getElementText());
                        pendingKey = null;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (parentElement.equals(local) && current != null) {
                        result.add(current);
                        current = null;
                    } else if (pairElement.equals(local)) {
                        inPair = false;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }
}
