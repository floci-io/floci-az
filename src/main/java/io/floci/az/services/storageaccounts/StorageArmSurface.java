package io.floci.az.services.storageaccounts;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link StorageAccountArmService} serves. */
@ApplicationScoped
public class StorageArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Storage";

    /**
     * 3 to 24 lowercase letters and digits, the {@code accountName} parameter of every storageAccounts operation
     * ({@code ^[a-z0-9]+$}, 3 to 24) at the 2025-08-01 api-version azurerm pins. Checked in every mode: floci-az has
     * always refused such a name with this code and message, before any name conflict.
     */
    static final NameRule ACCOUNT_NAME = new NameRule(3, 24, Pattern.compile("[a-z0-9]+"), "AccountNameInvalid",
        "%s is not a valid storage account name. Storage account name must be between 3 and 24 characters in length "
            + "and use numbers and lower-case letters only.").inEveryMode();

    /**
     * An account name is a global DNS label under {@code blob.core.windows.net}: taken in another subscription it is
     * {@code StorageAccountAlreadyTaken}, in another resource group of the caller's subscription
     * {@code StorageAccountInAnotherResourceGroup}.
     */
    static final GlobalName ACCOUNT_DNS_NAME = new GlobalName(CloudSuffix.BLOB, 409, "StorageAccountAlreadyTaken",
        "The storage account named %s is already taken.", "StorageAccountInAnotherResourceGroup",
        "The account %s is already in another resource group in this subscription.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "storageAccounts", LocationPolicy.REQUIRED)
                .withName(ACCOUNT_NAME)
                .withGlobalName(ACCOUNT_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "storageAccounts/blobServices"),
            ArmResourceType.child(NAMESPACE, "storageAccounts/blobServices/containers"),
            ArmResourceType.child(NAMESPACE, "storageAccounts/queueServices"),
            ArmResourceType.child(NAMESPACE, "storageAccounts/queueServices/queues"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
