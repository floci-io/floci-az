package io.floci.az.core;

/**
 * Fluent, allocation-efficient XML builder backed by a plain {@link StringBuilder}.
 *
 * <p>All content written via {@link #elem} and {@link #start} is automatically escaped.
 * Pre-built XML fragments can be injected without re-escaping via {@link #raw}.
 *
 * <pre>{@code
 * String xml = new XmlBuilder()
 *     .start("Response")
 *       .elem("Value", someString)
 *     .end("Response")
 *     .build();
 * }</pre>
 */
public final class XmlBuilder {

    private final StringBuilder sb = new StringBuilder();

    /** Opens {@code <element xmlns="xmlns">}. Omits the xmlns attribute when {@code xmlns} is null. */
    public XmlBuilder start(String element, String xmlns) {
        sb.append('<').append(element);
        if (xmlns != null) {
            sb.append(" xmlns=\"").append(xmlns).append('"');
        }
        sb.append('>');
        return this;
    }

    /** Opens {@code <element>} without a namespace. */
    public XmlBuilder start(String element) {
        return start(element, null);
    }

    /**
     * Opens {@code <element attr1="val1" attr2="val2" ...>}.
     * Attribute values are escaped. {@code nameValuePairs} must be an even-length array
     * of alternating name/value strings.
     */
    public XmlBuilder startAttr(String element, String... nameValuePairs) {
        sb.append('<').append(element);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            sb.append(' ').append(nameValuePairs[i]).append("=\"")
              .append(escape(nameValuePairs[i + 1])).append('"');
        }
        sb.append('>');
        return this;
    }

    /**
     * Appends a self-closing element: {@code <element attr1="val1" .../>}.
     * Attribute values are escaped. {@code nameValuePairs} must be even-length.
     */
    public XmlBuilder selfClose(String element, String... nameValuePairs) {
        sb.append('<').append(element);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            sb.append(' ').append(nameValuePairs[i]).append("=\"")
              .append(escape(nameValuePairs[i + 1])).append('"');
        }
        sb.append("/>");
        return this;
    }

    /** Appends {@code </element>}. */
    public XmlBuilder end(String element) {
        sb.append("</").append(element).append('>');
        return this;
    }

    /**
     * Appends {@code <name>escapedValue</name>}.
     * Skips the element entirely when {@code value} is {@code null}.
     */
    public XmlBuilder elem(String name, String value) {
        if (value == null) {
            return this;
        }
        sb.append('<').append(name).append('>')
          .append(escape(value))
          .append("</").append(name).append('>');
        return this;
    }

    /** Convenience overload — converts {@code long} to string. */
    public XmlBuilder elem(String name, long value) {
        return elem(name, String.valueOf(value));
    }

    /** Convenience overload — converts {@code boolean} to string. */
    public XmlBuilder elem(String name, boolean value) {
        return elem(name, String.valueOf(value));
    }

    /**
     * Appends a pre-built XML fragment verbatim, without escaping.
     * The caller is responsible for correctness of the fragment.
     */
    public XmlBuilder raw(String fragment) {
        if (fragment != null) {
            sb.append(fragment);
        }
        return this;
    }

    /** Returns the accumulated XML string. */
    public String build() {
        return sb.toString();
    }

    /**
     * Escapes the five XML special characters: {@code & < > " '}.
     * Returns an empty string for null or empty input.
     */
    public static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = null;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            String replacement = switch (c) {
                case '&'  -> "&amp;";
                case '<'  -> "&lt;";
                case '>'  -> "&gt;";
                case '"'  -> "&quot;";
                case '\'' -> "&apos;";
                default   -> null;
            };
            if (replacement != null) {
                if (out == null) {
                    out = new StringBuilder(len + 8);
                    out.append(s, 0, i);
                }
                out.append(replacement);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out != null ? out.toString() : s;
    }
}
