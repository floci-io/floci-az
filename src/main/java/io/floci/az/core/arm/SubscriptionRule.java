package io.floci.az.core.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Subscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.Set;

/**
 * The first rule of the gate: the subscription an ARM request names must exist and accept the request.
 *
 * <ul>
 *   <li>A subscription the directory holds in a writable state passes.</li>
 *   <li>An unknown subscription is registered on first use ({@code Enabled}, under the caller token's tenant
 *       when the directory holds it, otherwise the default tenant), unless subscriptions are strict
 *       ({@link EmulatorConfig#strictSubscriptions()}): then it is {@code 404 SubscriptionNotFound}, the
 *       answer Azure gives for an id it cannot find, malformed ones included.</li>
 *   <li>A subscription in a read-only state serves GET, HEAD and DELETE and refuses PUT, PATCH and POST, per the
 *       RPC subscription lifecycle contract. The status and code of that refusal have no local source:
 *       {@code 409 ReadOnlyDisabledSubscription} is an emulator choice.</li>
 * </ul>
 *
 * <p>A request whose path names no subscription ({@code GET /subscriptions}, {@code GET /tenants},
 * tenant-rooted providers) passes untouched. No id is ever refused for its format: clients that pass
 * their own non-GUID ids keep working, and Azure's own answer to a malformed id is the same
 * {@code SubscriptionNotFound} strict subscriptions already give.
 */
@ApplicationScoped
public class SubscriptionRule implements ArmRequestRule {

    /** Position in the gate: first, since every later rule assumes the subscription exists. */
    public static final int ORDER = 1;

    /** The status of a write refused on a read-only subscription; an emulator choice. */
    static final int READ_ONLY_STATUS = 409;

    /** The code of a write refused on a read-only subscription; an emulator choice. */
    static final String READ_ONLY_CODE = "ReadOnlyDisabledSubscription";

    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "DELETE");

    private final AzureDirectory directory;
    private final boolean strict;

    @Inject
    public SubscriptionRule(AzureDirectory directory, EmulatorConfig config) {
        this(directory, config.strictSubscriptions());
    }

    SubscriptionRule(AzureDirectory directory, boolean strict) {
        this.directory = directory;
        this.strict = strict;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        String subscriptionId = call.id() == null ? null : call.id().subscriptionId();
        if (subscriptionId == null) {
            return ArmVerdict.proceed();
        }
        Optional<Subscription> known = directory.subscription(subscriptionId);
        if (known.isEmpty()) {
            if (strict) {
                return ArmVerdict.respond(ArmErrors.subscriptionNotFound(subscriptionId));
            }
            directory.registerOnFirstUse(subscriptionId, call.scope() == null ? null : call.scope().tokenTenantId());
            return ArmVerdict.proceed();
        }
        Subscription subscription = known.get();
        if (subscription.state().allowsWrites() || READ_ONLY_METHODS.contains(call.method())) {
            return ArmVerdict.proceed();
        }
        return ArmVerdict.respond(ArmErrors.error(READ_ONLY_STATUS, READ_ONLY_CODE,
            "The subscription '" + subscription.id() + "' is " + subscription.state().wireName()
                + " and therefore marked as read only. You cannot perform any write actions on this subscription"
                + " until it is re-enabled."));
    }
}
