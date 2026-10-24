package io.floci.az.services.keyvault;

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

/** The ARM resource types {@link KeyVaultArmService} serves. */
@ApplicationScoped
public class KeyVaultArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.KeyVault";

    /** A letter, then letters, digits and single hyphens, ending with a letter or digit. */
    private static final Pattern VAULT_OR_HSM_NAME = Pattern.compile("[A-Za-z](?:[A-Za-z0-9]|-[A-Za-z0-9])+");

    /**
     * 3 to 24 letters, digits and hyphens, starting with a letter, ending with a letter or digit, no consecutive
     * hyphens: {@code resource-name-rules.md} (Microsoft.KeyVault, vaults). The 2026-02-01 spec azurerm pins declares
     * no pattern on the vault operations' {@code vaultName}, only {@code [a-zA-Z0-9-]{3,24}} on its child keys.
     */
    static final NameRule VAULT_NAME = new NameRule(3, 24, VAULT_OR_HSM_NAME, "InvalidResourceName",
        "The resource name '%s' is invalid: a vault name is 3 to 24 letters, digits and hyphens, starts with a letter, "
            + "ends with a letter or digit, and has no consecutive hyphens.");

    /**
     * The 2026-02-01 spec's managed HSM {@code name} pattern, {@code ^[A-Za-z]([A-Za-z0-9]|\-[A-Za-z0-9])+$} with
     * length 3 to 24 (declared on the {@code managedHSMs/{name}/keys} operations; the pool operations declare none).
     */
    static final NameRule HSM_NAME = new NameRule(3, 24, VAULT_OR_HSM_NAME, "InvalidResourceName",
        "The resource name '%s' is invalid: a managed HSM name is 3 to 24 letters, digits and hyphens, starts with a "
            + "letter, ends with a letter or digit, and has no consecutive hyphens.");

    /** A vault name is a global DNS label under {@code vault.azure.net}. */
    static final GlobalName VAULT_DNS_NAME = new GlobalName(CloudSuffix.KEYVAULT, 409, "VaultAlreadyExists",
        "The vault name '%s' is already in use. Vault names are globally unique so it is possible that the name is "
            + "already taken.");

    /**
     * A managed HSM name is a global DNS label under {@code managedhsm.azure.net}. No public source gives the create
     * conflict's code; ARM's generic {@code Conflict} stands in.
     */
    static final GlobalName HSM_DNS_NAME = new GlobalName(CloudSuffix.MHSM, 409, "Conflict",
        "The managed HSM name '%s' is already in use.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "vaults", LocationPolicy.REQUIRED)
                .withName(VAULT_NAME)
                .withGlobalName(VAULT_DNS_NAME),
            ArmResourceType.resourceGroup(NAMESPACE, "managedHSMs", LocationPolicy.REQUIRED)
                .withName(HSM_NAME)
                .withGlobalName(HSM_DNS_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
