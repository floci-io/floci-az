package io.floci.az.services.servicebus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What floci-az stores for the {@code Microsoft.ServiceBus} management plane. Queues, topics, subscriptions and rules
 * are not stored here: the entity store {@link ServiceBusHandler} keeps for the Atom API and the broker stays their
 * only record, and {@link ArmProperties} holds what ARM sends that the entity records do not model.
 */
public final class ServiceBusArmModels {

    private ServiceBusArmModels() {
    }

    /**
     * A {@code Microsoft.ServiceBus/namespaces} resource.
     *
     * @param id         the resource id, in the casing of the PUT that created it
     * @param name       the namespace name, which is also the name of its broker namespace
     * @param location   the location in canonical form
     * @param tags       the tags
     * @param sku        the SKU as Azure answers it: {@code name}, {@code tier} and, when sent, {@code capacity}
     * @param identity   the managed identity as sent, or null for none
     * @param properties the writable properties as the last PUT or PATCH left them
     * @param createdAt  when the namespace was created
     * @param updatedAt  when it was last written
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Namespace(
            String id,
            String name,
            String location,
            Map<String, Object> tags,
            Map<String, Object> sku,
            Map<String, Object> identity,
            Map<String, Object> properties,
            String createdAt,
            String updatedAt
    ) {
    }

    /**
     * A shared access authorization rule of a namespace, a queue or a topic ({@code SBAuthorizationRule}).
     *
     * @param id           the resource id, in the casing of the PUT that created it
     * @param name         the rule name
     * @param rights       the rights, each {@code Listen}, {@code Send} or {@code Manage}
     * @param primaryKey   the primary key, base64
     * @param secondaryKey the secondary key, base64
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorizationRule(String id, String name, List<String> rights, String primaryKey,
                                    String secondaryKey) {

        public AuthorizationRule {
            rights = rights == null ? List.of() : List.copyOf(rights);
        }

        public AuthorizationRule withKeys(String primary, String secondary) {
            return new AuthorizationRule(id, name, rights, primary, secondary);
        }
    }

    /**
     * Properties ARM sends that the entity store does not keep: the writable properties of a queue, a topic or a
     * subscription as the last ARM PUT sent them, or a namespace's network rule set.
     *
     * @param id         the resource id
     * @param properties the properties as sent
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArmProperties(String id, Map<String, Object> properties) {

        public ArmProperties {
            properties = properties == null ? Map.of() : properties;
        }
    }

    /**
     * A duration in the form Azure answers Service Bus durations with: {@code P14D}, {@code PT1M},
     * {@code P10675199DT2H48M5.4775807S}. {@link Duration#toString()} never writes days, so it would answer
     * {@code PT336H} for fourteen days, which a client comparing strings reads as a change.
     */
    static String isoDuration(Duration duration) {
        long days = duration.toDays();
        Duration rest = duration.minusDays(days);
        long hours = rest.toHours();
        long minutes = rest.toMinutesPart();
        long seconds = rest.toSecondsPart();
        int nanos = rest.toNanosPart();
        StringBuilder text = new StringBuilder("P");
        if (days > 0) {
            text.append(days).append('D');
        }
        if (hours > 0 || minutes > 0 || seconds > 0 || nanos > 0) {
            text.append('T');
            if (hours > 0) {
                text.append(hours).append('H');
            }
            if (minutes > 0) {
                text.append(minutes).append('M');
            }
            if (seconds > 0 || nanos > 0) {
                text.append(seconds);
                if (nanos > 0) {
                    String fraction = String.format("%09d", nanos).replaceAll("0+$", "");
                    text.append('.').append(fraction);
                }
                text.append('S');
            }
        }
        return text.length() == 1 ? "PT0S" : text.toString();
    }
}
