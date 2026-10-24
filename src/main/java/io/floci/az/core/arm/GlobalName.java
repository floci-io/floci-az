package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudSuffix;
import jakarta.ws.rs.core.Response;

/**
 * A type whose name is a global DNS label: one owner per name, kept by the {@link GlobalNameRegistry} and enforced on
 * PUT by {@link GlobalNameRule}. The conflict answer is the service's own literal, pinned by
 * {@code CrossSubscriptionIsolationTest}, never derived. {@link #NONE} declares no global name.
 *
 * <p>A name is unique across every cloud unless the type declares {@link #perCloud}. In Azure each cloud has its own
 * DNS zones, but floci-az keys most data planes by name alone (an ACR repository prefix, a storage account, a database
 * container), with no cloud in the key, so two clouds holding one name would share one data plane. Only a type whose
 * data plane is keyed by something else (Redis, a container per cache instance) is per cloud.
 *
 * @param suffix                  the DNS suffix the name lives under, which keeps each type's names apart
 * @param conflictStatus          the status of a PUT for a name another resource owns
 * @param conflictCode            its error code
 * @param conflictMessage         its error message, with {@code %s} for the name
 * @param sameSubscriptionCode    the code when the owner is in the caller's subscription (another resource group),
 *                                or null to answer the conflict above
 * @param sameSubscriptionMessage its message, with {@code %s} for the name, or null
 * @param perCloud                whether each cloud has its own owner of a name, as in Azure; only for a data plane
 *                                that does not key by the name
 * @param conflictResponder       the conflict in the service's own shape, for a service whose conflict is not an ARM
 *                                {@code CloudError} (App Service); null answers {@link ArmErrors#error} with the
 *                                status, code and message above
 */
public record GlobalName(CloudSuffix suffix, int conflictStatus, String conflictCode, String conflictMessage,
                         String sameSubscriptionCode, String sameSubscriptionMessage, boolean perCloud,
                         ConflictResponder conflictResponder) {

    /** The answer to a PUT of a name another resource owns, in a service's own error shape. */
    @FunctionalInterface
    public interface ConflictResponder {

        /** The conflict response for {@code name}. */
        Response conflict(String name);
    }

    /** No global name. */
    public static final GlobalName NONE = new GlobalName(null, 0, null, null, null, null, false, null);

    /** A global name answering its conflict as an ARM error. */
    public GlobalName(CloudSuffix suffix, int conflictStatus, String conflictCode, String conflictMessage,
                      String sameSubscriptionCode, String sameSubscriptionMessage, boolean perCloud) {
        this(suffix, conflictStatus, conflictCode, conflictMessage, sameSubscriptionCode, sameSubscriptionMessage,
            perCloud, null);
    }

    /** A global name answering one conflict whoever owns it, unique across clouds. */
    public GlobalName(CloudSuffix suffix, int conflictStatus, String conflictCode, String conflictMessage) {
        this(suffix, conflictStatus, conflictCode, conflictMessage, null, null, false);
    }

    /** A global name with a same-subscription answer, unique across clouds. */
    public GlobalName(CloudSuffix suffix, int conflictStatus, String conflictCode, String conflictMessage,
                      String sameSubscriptionCode, String sameSubscriptionMessage) {
        this(suffix, conflictStatus, conflictCode, conflictMessage, sameSubscriptionCode, sameSubscriptionMessage, false);
    }

    /** This global name, owned per cloud. */
    public GlobalName ownedPerCloud() {
        return new GlobalName(suffix, conflictStatus, conflictCode, conflictMessage, sameSubscriptionCode,
            sameSubscriptionMessage, true, conflictResponder);
    }

    /**
     * This global name, its conflict answered by {@code responder} instead of an ARM error. The status, code and
     * message stay declared, so the golden tables and docs read the same answer the responder sends.
     */
    public GlobalName answeredBy(ConflictResponder responder) {
        return new GlobalName(suffix, conflictStatus, conflictCode, conflictMessage, sameSubscriptionCode,
            sameSubscriptionMessage, perCloud, responder);
    }

    /** Whether the type declares one. */
    public boolean declared() {
        return suffix != null;
    }
}
