package io.floci.az.services.cosmos.engine;

import java.util.List;

/**
 * Describes protocol compatibility for a Cosmos engine.
 *
 * @param parityLevel        "high", "medium", or "limited"
 * @param unsupportedFeatures  Azure-specific features not supported by this engine
 * @param notes              Free-form compatibility notes
 */
public record CosmosCompatibilityMetadata(
        String parityLevel,
        List<String> unsupportedFeatures,
        String notes) {
}
