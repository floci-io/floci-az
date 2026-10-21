package io.floci.az.core.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The older {@code /metadata/endpoints} shapes for a cloud the emulator serves at its own base URL, built from the
 * cloud's catalog payload ({@link CloudProfile#metadataPayload}) with every endpoint a client calls replaced by the
 * emulator's. The audiences and suffixes of services no client reaches through this document stay Azure's.
 *
 * <ul>
 *   <li>{@value #CLOUD_LIST_VERSION}: a list of clouds, which the az CLI reads from {@code ARM_CLOUD_METADATA_URL}
 *       and registers by {@code name}; with one cloud listed, az makes it the active one with no
 *       {@code az cloud set}. The entry is named {@value #EMULATOR_CLOUD_NAME}, as the 2022-09-01 answer is, and
 *       never {@code AzureCloud}: az replaces the login endpoint of a cloud by that name with
 *       {@code https://login.microsoftonline.com}, which would send {@code az login} away from the emulator.</li>
 *   <li>{@value #CLOUD_ENDPOINT_VERSION}: one object whose {@code cloudEndpoint} lists each Azure ARM host's
 *       locations, verbatim. azurerm validates a resource's location only against the entry whose
 *       {@code endpoint} is its own ARM host, so naming no emulator host keeps its "any non-empty location"
 *       fallback.</li>
 * </ul>
 *
 * <p>The {@code 2022-09-01} answer is not built here: it predates the catalog and is pinned byte for byte.
 */
public final class CloudMetadata {

    public static final String CLOUD_LIST_VERSION = "2019-05-01";
    public static final String CLOUD_ENDPOINT_VERSION = "2018-01-01";
    /** The name every {@code /metadata/endpoints} answer gives the emulator's cloud. */
    public static final String EMULATOR_CLOUD_NAME = "floci-az";

    private CloudMetadata() {
    }

    /** The {@value #CLOUD_LIST_VERSION} answer: a one-cloud list for {@code cloud}, served at {@code baseUrl}. */
    public static ArrayNode cloudList(CloudProfile cloud, String baseUrl) {
        ObjectNode entry = payload(cloud, CLOUD_LIST_VERSION);
        entry.put("name", EMULATOR_CLOUD_NAME);
        // No trailing slash, as in the 2022-09-01 answer: SDKs append leading-slash paths to it.
        entry.put("resourceManager", baseUrl);
        entry.put("microsoftGraphResourceId", baseUrl + "/");
        entry.put("portal", baseUrl + "/");
        entry.put("gallery", baseUrl + "/");
        emulatorAuthentication(entry, baseUrl);
        return JsonNodeFactory.instance.arrayNode().add(entry);
    }

    /** The {@value #CLOUD_ENDPOINT_VERSION} answer for {@code cloud}, served at {@code baseUrl}. */
    public static ObjectNode cloudEndpoints(CloudProfile cloud, String baseUrl) {
        ObjectNode body = payload(cloud, CLOUD_ENDPOINT_VERSION);
        body.put("galleryEndpoint", baseUrl + "/");
        body.put("portalEndpoint", baseUrl + "/");
        emulatorAuthentication(body, baseUrl);
        return body;
    }

    /** Login and the one token audience at the emulator, as the 2022-09-01 answer advertises them. */
    private static void emulatorAuthentication(ObjectNode body, String baseUrl) {
        ObjectNode authentication = (ObjectNode) body.path("authentication");
        authentication.put("loginEndpoint", baseUrl + "/");
        authentication.set("audiences", JsonNodeFactory.instance.arrayNode().add(baseUrl + "/"));
    }

    private static ObjectNode payload(CloudProfile cloud, String apiVersion) {
        JsonNode payload = cloud.metadataPayload(apiVersion).orElseThrow(() -> new IllegalStateException(
            cloud.name() + " has no " + apiVersion + " metadata payload in " + CloudCatalog.RESOURCE));
        if (!(payload instanceof ObjectNode body) || !(body.path("authentication") instanceof ObjectNode)) {
            throw new IllegalStateException(cloud.name() + ": malformed " + apiVersion + " metadata payload in "
                + CloudCatalog.RESOURCE);
        }
        return body;
    }
}
