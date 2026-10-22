package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-cloud exception read from the real type declarations, not a hand-built set: a name is one across clouds
 * unless its type declares {@code ownedPerCloud()}, which only Redis does.
 */
@QuarkusTest
@DisplayName("GlobalNameRegistry: the per-cloud exception comes from the declared types")
class GlobalNameDeclarationTest {

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic("AzureCloud");
    private static final CloudProfile GOV = CloudCatalog.forNameOrPublic("AzureUSGovernment");

    @Inject
    GlobalNameRegistry registry;

    @BeforeEach
    @AfterEach
    void reset() {
        registry.clear();
    }

    private static ArmResourceId owner(String sub, String type, String name) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/rg/providers/" + type + "/" + name)
            .orElseThrow();
    }

    @Test
    void aRedisCacheNameIsOwnedPerCloud() {
        registry.reserve(PUBLIC, CloudSuffix.REDIS, "decl-cache", owner("pub", "Microsoft.Cache/Redis", "decl-cache"));

        assertEquals(GlobalNameRegistry.Outcome.RESERVED, registry.reserve(GOV, CloudSuffix.REDIS, "decl-cache",
            owner("gov", "Microsoft.Cache/Redis", "decl-cache")).outcome());
    }

    @Test
    void aRegistryNameIsOneAcrossClouds() {
        registry.reserve(PUBLIC, CloudSuffix.ACR, "declreg", owner("pub", "Microsoft.ContainerRegistry/registries",
            "declreg"));

        assertEquals(GlobalNameRegistry.Outcome.TAKEN, registry.reserve(GOV, CloudSuffix.ACR, "declreg",
            owner("gov", "Microsoft.ContainerRegistry/registries", "declreg")).outcome());
    }
}
