package io.floci.az.core.directory;

/**
 * A subscription's registration with a resource provider namespace, as the Providers API reports it in
 * {@code registrationState}. Azure moves through {@code Registering} and {@code Unregistering} too; floci-az
 * answers the final state at once, because {@code az provider register --wait} and the Python auto-register
 * policy poll it without a cap.
 */
public enum RegistrationState {

    /** Registered: requests to the namespace's resource types are served. */
    REGISTERED("Registered"),
    /** Never registered in this subscription. */
    NOT_REGISTERED("NotRegistered"),
    /** Unregistered after having been registered; the state {@code az provider unregister --wait} polls for. */
    UNREGISTERED("Unregistered");

    private final String wireName;

    RegistrationState(String wireName) {
        this.wireName = wireName;
    }

    /** The spelling on the wire. */
    public String wireName() {
        return wireName;
    }
}
