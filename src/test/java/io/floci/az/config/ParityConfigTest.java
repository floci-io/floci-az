package io.floci.az.config;

import io.floci.az.core.BannerLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code floci-az.parity} and {@code floci-az.parity-overrides}: every override resolves from the level as
 * documented, the levels nest, an explicit override wins either way, and {@code auth.mode} changes nothing.
 */
@DisplayName("Parity: levels, overrides and their resolvers")
class ParityConfigTest {

    /** Every override the config declares, with the lowest level that turns it on. */
    private static final Map<String, ParityLevel> ON_FROM = Map.of(
        "subscription-listing", ParityLevel.AZURE,
        "strict-subscriptions", ParityLevel.STRICT,
        "resource-group-semantics", ParityLevel.AZURE,
        "resource-group-delete", ParityLevel.AZURE,
        "strict-resource-groups", ParityLevel.STRICT);

    private record Override(String name, Predicate<EmulatorConfig> resolver) {
    }

    private static final List<Override> OVERRIDES = List.of(
        new Override("subscription-listing", EmulatorConfig::subscriptionListing),
        new Override("strict-subscriptions", EmulatorConfig::strictSubscriptions),
        new Override("resource-group-semantics", EmulatorConfig::resourceGroupSemantics),
        new Override("resource-group-delete", EmulatorConfig::resourceGroupDelete),
        new Override("strict-resource-groups", EmulatorConfig::strictResourceGroups));

    private static EmulatorConfig config(ParityLevel level, Map<String, Boolean> explicit, String authMode) {
        EmulatorConfig config = mock(EmulatorConfig.class, CALLS_REAL_METHODS);
        EmulatorConfig.ParityOverridesConfig overrides = mock(EmulatorConfig.ParityOverridesConfig.class);
        EmulatorConfig.AuthConfig auth = mock(EmulatorConfig.AuthConfig.class);
        when(config.parity()).thenReturn(level);
        when(config.parityOverrides()).thenReturn(overrides);
        when(config.auth()).thenReturn(auth);
        when(auth.mode()).thenReturn(authMode);
        when(overrides.subscriptionListing()).thenReturn(Optional.ofNullable(explicit.get("subscription-listing")));
        when(overrides.strictSubscriptions()).thenReturn(Optional.ofNullable(explicit.get("strict-subscriptions")));
        when(overrides.resourceGroupSemantics())
            .thenReturn(Optional.ofNullable(explicit.get("resource-group-semantics")));
        when(overrides.resourceGroupDelete()).thenReturn(Optional.ofNullable(explicit.get("resource-group-delete")));
        when(overrides.strictResourceGroups())
            .thenReturn(Optional.ofNullable(explicit.get("strict-resource-groups")));
        return config;
    }

    private static EmulatorConfig config(ParityLevel level) {
        return config(level, Map.of(), "dev");
    }

    @Test
    void everyOverrideIsListedWithTheLevelThatTurnsItOn() {
        assertEquals(ON_FROM.keySet(), Set.copyOf(OVERRIDES.stream().map(Override::name).toList()));
    }

    @Test
    void anUnsetOverrideIsOnExactlyFromItsLevel() {
        for (Override override : OVERRIDES) {
            for (ParityLevel level : ParityLevel.values()) {
                assertEquals(level.includes(ON_FROM.get(override.name())), override.resolver().test(config(level)),
                    override.name() + " under parity " + level);
            }
        }
    }

    @Test
    void compatTurnsEveryOverrideOff() {
        for (Override override : OVERRIDES) {
            assertFalse(override.resolver().test(config(ParityLevel.COMPAT)), override.name());
        }
    }

    @Test
    void strictTurnsEveryOverrideOn() {
        for (Override override : OVERRIDES) {
            assertTrue(override.resolver().test(config(ParityLevel.STRICT)), override.name());
        }
    }

    @Test
    void theLevelsNest() {
        assertTrue(ParityLevel.STRICT.includes(ParityLevel.AZURE));
        assertTrue(ParityLevel.AZURE.includes(ParityLevel.COMPAT));
        assertFalse(ParityLevel.AZURE.includes(ParityLevel.STRICT));
        assertFalse(ParityLevel.COMPAT.includes(ParityLevel.AZURE));
        for (Override override : OVERRIDES) {
            for (ParityLevel lower : ParityLevel.values()) {
                for (ParityLevel higher : ParityLevel.values()) {
                    if (higher.includes(lower) && override.resolver().test(config(lower))) {
                        assertTrue(override.resolver().test(config(higher)),
                            override.name() + " on under " + lower + " but off under " + higher);
                    }
                }
            }
        }
    }

    @Test
    void anExplicitOverrideWinsEitherWay() {
        for (Override override : OVERRIDES) {
            for (ParityLevel level : ParityLevel.values()) {
                assertTrue(override.resolver().test(config(level, Map.of(override.name(), true), "dev")),
                    override.name() + "=true under " + level);
                assertFalse(override.resolver().test(config(level, Map.of(override.name(), false), "dev")),
                    override.name() + "=false under " + level);
            }
        }
    }

    @Test
    void authModeChangesNothing() {
        for (ParityLevel level : ParityLevel.values()) {
            for (Override override : OVERRIDES) {
                EmulatorConfig dev = config(level, Map.of(), "dev");
                EmulatorConfig strict = config(level, Map.of(), "strict");
                assertEquals(override.resolver().test(dev), override.resolver().test(strict),
                    override.name() + " under " + level);
                verify(strict, never()).auth();
            }
        }
    }

    @Test
    void theBannerShowsTheLevelAndOnlyTheOverridesThatDifferFromIt() {
        assertEquals("parity: compat", BannerLogger.parityLine(config(ParityLevel.COMPAT)));
        assertEquals("parity: strict", BannerLogger.parityLine(config(ParityLevel.STRICT)));
        assertEquals("parity: compat  subscription-listing:true",
            BannerLogger.parityLine(config(ParityLevel.COMPAT, Map.of("subscription-listing", true), "dev")));
        assertEquals("parity: azure",
            BannerLogger.parityLine(config(ParityLevel.AZURE, Map.of("subscription-listing", true), "dev")));
        assertEquals("parity: strict  strict-subscriptions:false",
            BannerLogger.parityLine(config(ParityLevel.STRICT, Map.of("strict-subscriptions", false), "dev")));
        assertEquals("parity: compat  resource-group-delete:true",
            BannerLogger.parityLine(config(ParityLevel.COMPAT, Map.of("resource-group-delete", true), "dev")));
        assertEquals("parity: azure  resource-group-semantics:false",
            BannerLogger.parityLine(config(ParityLevel.AZURE, Map.of("resource-group-semantics", false), "dev")));
        assertEquals("parity: azure  strict-resource-groups:true",
            BannerLogger.parityLine(config(ParityLevel.AZURE, Map.of("strict-resource-groups", true), "dev")));
    }
}
