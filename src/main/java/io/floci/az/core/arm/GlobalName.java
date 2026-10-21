package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudSuffix;

/**
 * A type whose name is a global DNS label: one owner per name in each cloud, kept by the
 * {@link GlobalNameRegistry} and enforced on PUT by {@link GlobalNameRule}. The conflict answer is the service's
 * own literal, pinned by {@code CrossSubscriptionIsolationTest}, never derived. {@link #NONE} declares no global name.
 *
 * @param suffix                  the DNS suffix the name lives under, which keeps each type's names apart
 * @param conflictStatus          the status of a PUT for a name another resource owns
 * @param conflictCode            its error code
 * @param conflictMessage         its error message, with {@code %s} for the name
 * @param sameSubscriptionCode    the code when the owner is in the caller's subscription (another resource group),
 *                                or null to answer the conflict above
 * @param sameSubscriptionMessage its message, with {@code %s} for the name, or null
 */
public record GlobalName(CloudSuffix suffix, int conflictStatus, String conflictCode, String conflictMessage,
                         String sameSubscriptionCode, String sameSubscriptionMessage) {

    /** No global name. */
    public static final GlobalName NONE = new GlobalName(null, 0, null, null, null, null);

    /** A global name answering one conflict whoever owns it. */
    public GlobalName(CloudSuffix suffix, int conflictStatus, String conflictCode, String conflictMessage) {
        this(suffix, conflictStatus, conflictCode, conflictMessage, null, null);
    }

    /** Whether the type declares one. */
    public boolean declared() {
        return suffix != null;
    }
}
