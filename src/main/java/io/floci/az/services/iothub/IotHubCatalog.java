package io.floci.az.services.iothub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed answers of the provider's read-only views: its operations, and the per-SKU quotas a hub's
 * {@code quotaMetrics} reports.
 */
final class IotHubCatalog {

    /** "Total number of messages/day" per unit, from Learn's IoT Hub tiers and sizes; F1 has one unit. */
    private static final Map<String, Long> MESSAGES_PER_UNIT = Map.of(
            "F1", 8_000L,
            "S1", 400_000L, "S2", 6_000_000L, "S3", 300_000_000L,
            "B1", 400_000L, "B2", 6_000_000L, "B3", 300_000_000L);
    /** The devices a hub can register: 1,000,000 as Azure answered the az CLI for an S1 hub, 500 on the free tier. */
    private static final long MAX_DEVICES = 1_000_000L;
    private static final long MAX_DEVICES_FREE = 500L;

    /**
     * {@code Operations_List}: the IoT Hub entries of the spec's example (2023-06-30), without the Device Provisioning
     * Service's, which floci-az does not serve.
     */
    static final List<Map<String, Object>> OPERATIONS = List.of(
            operation("register/action", "Register Resource Provider",
                    "Register the subscription for the IotHub resource provider and enables the creation of IotHub resources"),
            operation("IotHubs/diagnosticSettings/read", "Get Diagnostic Setting",
                    "Gets the diagnostic setting for the resource"),
            operation("IotHubs/diagnosticSettings/write", "Set Diagnostic Setting",
                    "Creates or updates the diagnostic setting for the resource"),
            operation("IotHubs/metricDefinitions/read", "Read IotHub service metric definitions",
                    "Gets the available metrics for the IotHub service"),
            operation("IotHubs/logDefinitions/read", "Read IotHub service log definitions",
                    "Gets the available log definitions for the IotHub Service"),
            operation("operations/Read", "Get All ResourceProvider Operations",
                    "Get All ResourceProvider Operations"),
            operation("checkNameAvailability/Action", "Check If IotHub name is available",
                    "Check If IotHub name is available"),
            operation("usages/Read", "Get Subscription Usages",
                    "Get subscription usage details for this provider."),
            operation("iotHubs/Read", "Get IotHub(s)",
                    "Gets the IotHub resource(s)"),
            operation("iotHubs/Write", "Create or update IotHub Resource",
                    "Create or update IotHub Resource"),
            operation("iotHubs/Delete", "Delete IotHub Resource",
                    "Delete IotHub Resource"),
            operation("iotHubs/iotHubStats/Read", "Get IotHub Statistics",
                    "Get IotHub Statistics"),
            operation("iotHubs/skus/Read", "Get valid IotHub Skus",
                    "Get valid IotHub Skus"),
            operation("iotHubs/listkeys/Action", "Get all IotHub Keys",
                    "Get all IotHub Keys"),
            operation("iotHubs/iotHubKeys/listkeys/Action", "Get IotHub Key for the given name",
                    "Get IotHub Key for the given name"),
            operation("iotHubs/eventHubEndpoints/consumerGroups/Write", "Create EventHub Consumer Group",
                    "Create EventHub Consumer Group"),
            operation("iotHubs/eventHubEndpoints/consumerGroups/Read", "Get EventHub Consumer Group(s)",
                    "Get EventHub Consumer Group(s)"),
            operation("iotHubs/eventHubEndpoints/consumerGroups/Delete", "Delete EventHub Consumer Group",
                    "Delete EventHub Consumer Group"),
            operation("iotHubs/exportDevices/Action", "Export Devices",
                    "Export Devices"),
            operation("iotHubs/importDevices/Action", "Import Devices",
                    "Import Devices"),
            operation("iotHubs/jobs/Read", "Get the Job(s) on IotHub",
                    "Get Job(s) details submitted on given IotHub"),
            operation("iotHubs/quotaMetrics/Read", "Get Quota Metrics",
                    "Get Quota Metrics"),
            operation("iotHubs/routing/routes/$testall/Action", "Routing Rule Test All",
                    "Test a message against all existing Routes"),
            operation("iotHubs/routing/routes/$testnew/Action", "Routing Rule Test Route",
                    "Test a message against a provided test Route"),
            operation("iotHubs/routingEndpointsHealth/Read", "Get Endpoint Health",
                    "Gets the health of all routing Endpoints for an IotHub"));

    private IotHubCatalog() {
    }

    /** {@code IotHubQuotaMetricInfo} entries for a hub: nothing counted yet, the maximums its SKU and units allow. */
    static List<Map<String, Object>> quotaMetrics(String sku, int capacity) {
        long devices = "F1".equals(sku) ? MAX_DEVICES_FREE : MAX_DEVICES;
        return List.of(metric("TotalMessages", MESSAGES_PER_UNIT.getOrDefault(sku, 0L) * capacity),
                metric("TotalDeviceCount", devices));
    }

    /** {@code RegistryStatistics}: floci-az has no device registry yet, so a hub has no devices. */
    static Map<String, Object> registryStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDeviceCount", 0);
        stats.put("enabledDeviceCount", 0);
        stats.put("disabledDeviceCount", 0);
        return stats;
    }

    private static Map<String, Object> metric(String name, long maxValue) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("name", name);
        metric.put("currentValue", 0);
        metric.put("maxValue", maxValue);
        return metric;
    }

    private static Map<String, Object> operation(String name, String operation, String description) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("provider", "Microsoft Devices");
        display.put("resource", "IotHubs");
        display.put("operation", operation);
        display.put("description", description);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "Microsoft.Devices/" + name);
        entry.put("display", display);
        return entry;
    }
}
