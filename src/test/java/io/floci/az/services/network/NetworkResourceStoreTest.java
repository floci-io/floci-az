package io.floci.az.services.network;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Network: resources in the network backend, keyed by canonical id")
class NetworkResourceStoreTest {

    private static final String API = "?api-version=2023-09-01";
    private static final String DNS_API = "?api-version=2018-05-01";
    private static final String VNET = "{\"location\":\"eastus\",\"properties\":{\"addressSpace\":"
        + "{\"addressPrefixes\":[\"10.0.0.0/16\"]}}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String provider(String sub, String rg) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network";
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneVirtualNetwork() {
        String first = provider("sub-case", "RG-Case") + "/virtualNetworks/case-vnet";
        given().contentType("application/json").body(VNET).when().put(first + API).then().statusCode(200);

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"k\":\"v\"}}")
            .when().put(provider("SUB-CASE", "rg-case") + "/virtualnetworks/CASE-VNET" + API)
            .then().statusCode(200)
            .body("id", equalTo(first))
            .body("name", equalTo("case-vnet"))
            .body("type", equalTo("Microsoft.Network/virtualNetworks"))
            .body("tags.k", equalTo("v"));

        given().when().get(provider("sub-case", "rg-case") + "/virtualNetworks" + API)
            .then().statusCode(200).body("value", hasSize(1)).body("value[0].id", equalTo(first));
        given().when().get(provider("sub-case", "rg-case") + "/virtualNetworks/Case-Vnet" + API)
            .then().statusCode(200).body("tags.k", equalTo("v"));
    }

    @Test
    void subnetsUnderOtherCasingBelongToTheOneVirtualNetworkAndGoWithIt() {
        String vnet = provider("sub-case", "rg-sub") + "/virtualNetworks/parent-vnet";
        given().contentType("application/json").body(VNET).when().put(vnet + API).then().statusCode(200);
        given().contentType("application/json").body("{\"properties\":{\"addressPrefix\":\"10.0.1.0/24\"}}")
            .when().put(vnet + "/subnets/One" + API).then().statusCode(200);
        given().contentType("application/json").body("{\"properties\":{\"addressPrefix\":\"10.0.1.0/24\"}}")
            .when().put(provider("sub-case", "RG-SUB") + "/virtualNetworks/PARENT-VNET/subnets/one" + API)
            .then().statusCode(200).body("id", equalTo(vnet + "/subnets/One"));

        given().when().get(provider("sub-case", "RG-SUB") + "/virtualNetworks/Parent-Vnet/subnets" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().when().delete(provider("SUB-CASE", "rg-sub") + "/virtualNetworks/PARENT-vnet" + API)
            .then().statusCode(200);
        given().when().get(vnet + "/subnets/One" + API).then().statusCode(404);
    }

    @Test
    void aRecordSetThroughOtherCasingIsCountedOnce() {
        String zone = provider("sub-dns", "rg-dns") + "/dnsZones/example.com";
        given().contentType("application/json").body("{\"location\":\"global\"}")
            .when().put(zone + DNS_API).then().statusCode(201);
        given().contentType("application/json").body("{\"location\":\"global\"}")
            .when().put(provider("sub-dns", "RG-DNS") + "/dnsZones/Example.com" + DNS_API)
            .then().statusCode(200).body("name", equalTo("example.com"));

        String record = "{\"properties\":{\"TTL\":300,\"aRecords\":[{\"ipv4Address\":\"10.0.0.1\"}]}}";
        given().contentType("application/json").body(record).when().put(zone + "/A/www" + DNS_API)
            .then().statusCode(201);
        given().contentType("application/json").body(record)
            .when().put(provider("SUB-DNS", "rg-dns") + "/dnsZones/EXAMPLE.COM/A/WWW" + DNS_API)
            .then().statusCode(200).body("id", equalTo(zone + "/A/www"));

        given().when().get(zone + DNS_API).then().statusCode(200).body("properties.numberOfRecordSets", equalTo(3));
        given().when().get(zone + "/recordsets" + DNS_API).then().statusCode(200).body("value", hasSize(3));
    }

    @Test
    void concurrentCreatesOfOneRecordSetCountItOnce() {
        String zone = provider("sub-dns", "rg-race") + "/dnsZones/race.example";
        given().contentType("application/json").body("{\"location\":\"global\"}")
            .when().put(zone + DNS_API).then().statusCode(201);

        String record = "{\"properties\":{\"TTL\":300,\"aRecords\":[{\"ipv4Address\":\"10.0.0.1\"}]}}";
        List<CompletableFuture<Integer>> puts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            puts.add(CompletableFuture.supplyAsync(() -> given().contentType("application/json").body(record)
                .when().put(zone + "/A/www" + DNS_API).then().extract().statusCode()));
        }
        long created = puts.stream().map(CompletableFuture::join).filter(status -> status == 201).count();

        assertEquals(1, created, "exactly one PUT creates the record set");
        given().when().get(zone + DNS_API).then().statusCode(200).body("properties.numberOfRecordSets", equalTo(3));
    }

    @Test
    void resourcesAreStoredUnderCanonicalKeysWithoutInternalFields() {
        String nsg = provider("sub-store", "rg-store") + "/networkSecurityGroups/Store-Nsg";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(nsg + API).then().statusCode(200);

        StorageBackend<String, StoredObject> backend = storageFactory.create(NetworkService.STORE);
        assertTrue(backend.keys().contains(nsg.toLowerCase()));
        String stored = new String(backend.get(nsg.toLowerCase()).orElseThrow().data(), StandardCharsets.UTF_8);
        assertFalse(stored.contains("\"_sub\""), stored);
    }

    @Test
    void aPrivateEndpointNoLongerEchoesItsInternalNicKey() {
        String pe = provider("sub-pe", "rg-pe") + "/privateEndpoints/pe1";
        String nic = given().contentType("application/json").body("{\"location\":\"eastus\",\"properties\":{}}")
            .when().put(pe + API).then().statusCode(201).body("_nic", nullValue())
            .extract().path("properties.networkInterfaces[0].id");

        given().when().get(pe + API).then().statusCode(200).body("_nic", nullValue());
        given().when().get(nic + API).then().statusCode(200);
        given().when().delete(pe + API).then().statusCode(200);
        given().when().get(nic + API).then().statusCode(404);
    }

    @Test
    void aSubscriptionLevelListSpansResourceGroupsAndAPathWithoutAGroupAddressesNothing() {
        given().contentType("application/json").body(VNET)
            .when().put(provider("sub-list", "rg-a") + "/virtualNetworks/vnet-a" + API).then().statusCode(200);
        given().contentType("application/json").body(VNET)
            .when().put(provider("sub-list", "rg-b") + "/virtualNetworks/vnet-b" + API).then().statusCode(200);

        given().when().get("/subscriptions/sub-list/providers/Microsoft.Network/virtualNetworks" + API)
            .then().statusCode(200).body("value.name", containsInAnyOrder("vnet-a", "vnet-b"));
        given().contentType("application/json").body(VNET)
            .when().put("/subscriptions/sub-list/providers/Microsoft.Network/virtualNetworks/no-rg" + API)
            .then().statusCode(404);
    }
}
