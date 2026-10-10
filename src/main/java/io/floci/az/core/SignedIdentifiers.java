package io.floci.az.core;

import org.jboss.logging.Logger;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code SignedIdentifiers} document that the Storage ACL operations ({@code ?comp=acl} on a
 * queue or a table) exchange: up to five stored access policies, each an {@code Id} plus an optional
 * {@code AccessPolicy} of {@code Start}, {@code Expiry} and {@code Permission}.
 *
 * <p>Start and Expiry are kept as the strings the client sent, so a policy reads back exactly as it was
 * stored, whichever date-time precision the SDK chose.
 */
public final class SignedIdentifiers {

    /** A container, queue or table holds at most five stored access policies. */
    public static final int MAX_IDENTIFIERS = 5;

    private static final Logger LOG = Logger.getLogger(SignedIdentifiers.class);
    private static final String XML_PROLOG = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    private static final String ROOT = "SignedIdentifiers";
    private static final String ENTRY = "SignedIdentifier";

    public record SignedIdentifier(String id, AccessPolicy accessPolicy) {}

    public record AccessPolicy(String start, String expiry, String permission) {}

    private SignedIdentifiers() {}

    /**
     * Parses a {@code SignedIdentifiers} request body. A blank body is an empty list, which is how a
     * client clears every stored policy.
     *
     * @throws XMLStreamException when the body is not well-formed or its root is not {@code SignedIdentifiers}
     */
    public static List<SignedIdentifier> parse(String xml) throws XMLStreamException {
        List<SignedIdentifier> identifiers = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return identifiers;
        }
        XMLStreamReader r = XmlParser.newStreamReader(xml.strip());
        try {
            boolean sawRoot = false;
            String id = null;
            String start = null;
            String expiry = null;
            String permission = null;
            boolean hasPolicy = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if (!sawRoot) {
                        if (!ROOT.equals(name)) {
                            throw new XMLStreamException("Root element must be " + ROOT + ", was " + name);
                        }
                        sawRoot = true;
                        continue;
                    }
                    switch (name) {
                        case ENTRY -> {
                            id = null;
                            start = null;
                            expiry = null;
                            permission = null;
                            hasPolicy = false;
                        }
                        case "AccessPolicy" -> hasPolicy = true;
                        case "Id" -> id = r.getElementText();
                        case "Start" -> start = r.getElementText();
                        case "Expiry" -> expiry = r.getElementText();
                        case "Permission" -> permission = r.getElementText();
                        default -> LOG.debugv("Ignoring unknown {0} element {1}", ROOT, name);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && ENTRY.equals(r.getLocalName())) {
                    AccessPolicy policy = hasPolicy || start != null || expiry != null || permission != null
                            ? new AccessPolicy(start, expiry, permission)
                            : null;
                    identifiers.add(new SignedIdentifier(id, policy));
                }
            }
            if (!sawRoot) {
                throw new XMLStreamException("Missing " + ROOT + " element");
            }
        } finally {
            r.close();
        }
        return identifiers;
    }

    /**
     * Whether a parsed list is acceptable to the service: at most {@link #MAX_IDENTIFIERS} entries, each
     * with an Id, and every permission letter drawn from {@code allowedPermissions} (for example
     * {@code "raup"} for a queue, {@code "raud"} for a table).
     */
    public static boolean isValid(List<SignedIdentifier> identifiers, String allowedPermissions) {
        if (identifiers.size() > MAX_IDENTIFIERS) {
            return false;
        }
        for (SignedIdentifier identifier : identifiers) {
            if (identifier.id() == null || identifier.id().isBlank()) {
                return false;
            }
            AccessPolicy policy = identifier.accessPolicy();
            if (policy != null && policy.permission() != null) {
                for (char letter : policy.permission().toCharArray()) {
                    if (allowedPermissions.indexOf(letter) < 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Serialises the list as the {@code SignedIdentifiers} response document. */
    public static String toXml(List<SignedIdentifier> identifiers) {
        XmlBuilder xml = new XmlBuilder().raw(XML_PROLOG).start(ROOT);
        for (SignedIdentifier identifier : identifiers) {
            xml.start(ENTRY).elem("Id", identifier.id());
            AccessPolicy policy = identifier.accessPolicy();
            if (policy != null) {
                xml.start("AccessPolicy")
                        .elem("Start", policy.start())
                        .elem("Expiry", policy.expiry())
                        .elem("Permission", policy.permission())
                        .end("AccessPolicy");
            }
            xml.end(ENTRY);
        }
        return xml.end(ROOT).build();
    }

    /** The document for a resource that has no stored access policies. */
    public static String emptyXml() {
        return toXml(List.of());
    }

    /** The error Azure returns for an unparsable or out-of-bounds {@code SignedIdentifiers} body. */
    public static AzureErrorResponse invalidXmlDocument() {
        return new AzureErrorResponse("InvalidXmlDocument", "XML specified is not syntactically valid.");
    }
}
