package io.floci.az.core.directory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Arrays;

/** A subscription's state, spelled on the wire exactly as {@code subscriptions.json} (2022-12-01) declares it. */
@RegisterForReflection
public enum SubscriptionState {
    ENABLED("Enabled"),
    WARNED("Warned"),
    PAST_DUE("PastDue"),
    DISABLED("Disabled"),
    DELETED("Deleted");

    private final String wireName;

    SubscriptionState(String wireName) {
        this.wireName = wireName;
    }

    /** The value of {@code state} in a subscription body. */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Whether management writes (PUT, PATCH, POST) are served. The RPC subscription lifecycle contract
     * allows only GET and DELETE on a warned or suspended subscription. {@code PastDue} is not an RPC state:
     * floci-az treats it as a billing notice that leaves the subscription writable, an emulator choice.
     * {@code Deleted} is treated as read-only too, also an emulator choice: nothing cascades its resources yet.
     */
    public boolean allowsWrites() {
        return this == ENABLED || this == PAST_DUE;
    }

    /** Parses a wire name, ignoring case. */
    @JsonCreator
    public static SubscriptionState fromWireName(String value) {
        for (SubscriptionState state : values()) {
            if (state.wireName.equalsIgnoreCase(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown subscription state '" + value + "'; expected one of "
            + Arrays.stream(values()).map(SubscriptionState::wireName).toList());
    }
}
