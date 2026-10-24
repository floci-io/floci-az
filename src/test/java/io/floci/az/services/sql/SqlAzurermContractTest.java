package io.floci.az.services.sql;

import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The calls azurerm's {@code azurerm_mssql_server} and {@code azurerm_mssql_database} make (3.x at
 * 2023-02-01-preview, 4.x and 5.x at 2023-08-01-preview), with the shapes its go-azure-sdk models decode: the
 * server and database bodies, the settings each reads on every refresh, and the ARM error envelope.
 */
@QuarkusTest
class SqlAzurermContractTest {

    private static final String API = "?api-version=2023-08-01-preview";
    private static final String BASE = "/subscriptions/sub-azurerm/resourceGroups/rg-azurerm/providers/Microsoft.Sql";
    private static final String SERVER = BASE + "/servers/tfserver";
    private static final String DATABASE = SERVER + "/databases/tfdb";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void createServer(String body) {
        given().contentType("application/json").body(body)
            .when().put(SERVER + API)
            .then().statusCode(201);
    }

    private static void createServer() {
        createServer("{\"location\":\"eastus\",\"properties\":{\"administratorLogin\":\"sqladmin\","
            + "\"administratorLoginPassword\":\"FlociAz_Strong123!\",\"version\":\"12.0\"}}");
    }

    private static void createDatabase() {
        createServer();
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"S0\"},"
                + "\"properties\":{\"requestedBackupStorageRedundancy\":\"Geo\"}}")
            .when().put(DATABASE + API)
            .then().statusCode(201);
    }

    // ── Error envelope ───────────────────────────────────────────────────────

    @Test
    void anUnknownChildPathIsAnArmResourceNotFound() {
        createServer();
        given().when().get(SERVER + "/noSuchChild/default" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", containsString("noSuchChild"));
    }

    @Test
    void anUnsupportedMethodIsAnArmMethodNotAllowed() {
        createServer();
        given().when().post(SERVER + API)
            .then().statusCode(405)
            .body("error.code", equalTo("MethodNotAllowed"));
    }

    // ── Server body ──────────────────────────────────────────────────────────

    @Test
    void aServerEchoesItsNetworkSettings() {
        createServer("{\"location\":\"eastus\",\"properties\":{\"administratorLogin\":\"sqladmin\","
            + "\"administratorLoginPassword\":\"FlociAz_Strong123!\",\"version\":\"12.0\","
            + "\"minimalTlsVersion\":\"1.2\",\"publicNetworkAccess\":\"Disabled\","
            + "\"restrictOutboundNetworkAccess\":\"Enabled\"}}");

        given().when().get(SERVER + API)
            .then().statusCode(200)
            .body("properties.minimalTlsVersion", equalTo("1.2"))
            .body("properties.publicNetworkAccess", equalTo("Disabled"))
            .body("properties.restrictOutboundNetworkAccess", equalTo("Enabled"));
    }

    @Test
    void aServerCreatedWithoutNetworkSettingsReadsAzureDefaults() {
        createServer();

        given().when().get(SERVER + API)
            .then().statusCode(200)
            .body("properties.minimalTlsVersion", equalTo("None"))
            .body("properties.publicNetworkAccess", equalTo("Enabled"))
            .body("properties.restrictOutboundNetworkAccess", equalTo("Disabled"));
    }

    @Test
    void anUpdateWithoutThePasswordKeepsTheServerAndItsSettings() {
        createServer("{\"location\":\"eastus\",\"properties\":{\"administratorLogin\":\"sqladmin\","
            + "\"administratorLoginPassword\":\"FlociAz_Strong123!\",\"minimalTlsVersion\":\"1.2\"}}");

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"},"
                + "\"properties\":{\"administratorLogin\":\"sqladmin\",\"version\":\"12.0\"}}")
            .when().put(SERVER + API)
            .then().statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("properties.minimalTlsVersion", equalTo("1.2"));
    }

    // ── Database body ────────────────────────────────────────────────────────

    @Test
    void aDatabaseBodyHasTheTypesTheGoModelsDecode() {
        createDatabase();

        given().when().get(DATABASE + API)
            .then().statusCode(200)
            .body("properties.maxSizeBytes", equalTo(1073741824))
            .body("properties.currentServiceObjectiveName", equalTo("S0"))
            .body("properties.requestedServiceObjectiveName", equalTo("S0"))
            .body("properties.requestedBackupStorageRedundancy", equalTo("Geo"))
            .body("properties.currentBackupStorageRedundancy", equalTo("Geo"))
            .body("properties.zoneRedundant", equalTo(false))
            .body("properties.status", equalTo("Online"));
    }

    @Test
    void aDatabaseEchoesTheSizeRedundancyAndTagsItWasCreatedWith() {
        createServer();
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"team\":\"data\"},\"sku\":{\"name\":\"S1\"},"
                + "\"properties\":{\"maxSizeBytes\":2147483648,\"requestedBackupStorageRedundancy\":\"Local\"}}")
            .when().put(DATABASE + API)
            .then().statusCode(201)
            .body("properties.maxSizeBytes", equalTo(2147483648L))
            .body("properties.requestedBackupStorageRedundancy", equalTo("Local"))
            .body("properties.currentServiceObjectiveName", equalTo("S1"))
            .body("tags.team", equalTo("data"));
    }

    @Test
    void aDatabaseUpdateKeepsItsIdAndSettings() {
        createDatabase();
        String databaseId = given().when().get(DATABASE + API).then().extract().path("properties.databaseId");
        given().contentType("application/json")
            .body("{\"properties\":{\"state\":\"Disabled\"}}")
            .when().put(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200);

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"S2\"},\"properties\":{}}")
            .when().put(DATABASE + API)
            .then().statusCode(200)
            .body("properties.databaseId", equalTo(databaseId))
            .body("properties.currentServiceObjectiveName", equalTo("S2"));

        given().when().get(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200)
            .body("properties.state", equalTo("Disabled"));
    }

    @Test
    void aDatabasePatchChangesOnlyTheFieldsItNames() {
        createServer();
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"S1\"},"
                + "\"properties\":{\"requestedBackupStorageRedundancy\":\"Zone\"}}")
            .when().put(DATABASE + API)
            .then().statusCode(201);

        given().contentType("application/json")
            .body("{\"properties\":{\"maxSizeBytes\":5368709120}}")
            .when().patch(DATABASE + API)
            .then().statusCode(200)
            .body("properties.maxSizeBytes", equalTo(5368709120L))
            .body("properties.requestedBackupStorageRedundancy", equalTo("Zone"))
            .body("properties.currentServiceObjectiveName", equalTo("S1"));
    }

    // ── Server settings ──────────────────────────────────────────────────────

    @Test
    void theConnectionPolicyStoresItsConnectionType() {
        createServer();
        given().when().get(SERVER + "/connectionPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.connectionType", equalTo("Default"));

        given().contentType("application/json")
            .body("{\"properties\":{\"connectionType\":\"Redirect\"}}")
            .when().put(SERVER + "/connectionPolicies/default" + API)
            .then().statusCode(200)
            .body("type", equalTo("Microsoft.Sql/servers/connectionPolicies"))
            .body("properties.connectionType", equalTo("Redirect"));

        given().when().get(SERVER + "/connectionPolicies/default" + API)
            .then().statusCode(200)
            .body("id", equalTo("/subscriptions/sub-azurerm/resourceGroups/rg-azurerm/providers/Microsoft.Sql"
                + "/servers/tfserver/connectionPolicies/default"))
            .body("location", equalTo("eastus"))
            .body("properties.connectionType", equalTo("Redirect"));
    }

    @Test
    void theExpressVulnerabilityAssessmentIsDisabledUntilEnabled() {
        createServer();
        given().when().get(SERVER + "/sqlVulnerabilityAssessments/default" + API)
            .then().statusCode(200)
            .body("name", equalTo("default"))
            .body("type", equalTo("Microsoft.Sql/servers/sqlVulnerabilityAssessments"))
            .body("properties.state", equalTo("Disabled"));

        given().contentType("application/json")
            .body("{\"properties\":{\"state\":\"Enabled\"}}")
            .when().put(SERVER + "/sqlVulnerabilityAssessments/default" + API)
            .then().statusCode(200)
            .body("properties.state", equalTo("Enabled"));

        given().when().get(SERVER + "/sqlVulnerabilityAssessments" + API)
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].properties.state", equalTo("Enabled"));
    }

    @Test
    void aServerHasNoRestorableDroppedDatabases() {
        createServer();
        given().when().get(SERVER + "/restorableDroppedDatabases" + API)
            .then().statusCode(200)
            .body("value", empty());

        given().when().get(SERVER + "/restorableDroppedDatabases/tfdb,131403269876900000" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aSettingOfAnUnknownServerIsNotFound() {
        given().when().get(BASE + "/servers/ghost/sqlVulnerabilityAssessments/default" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    // ── Database settings ────────────────────────────────────────────────────

    @Test
    void everyDatabaseSettingReadsItsDefaultsBeforeAnyWrite() {
        createDatabase();

        given().when().get(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200)
            .body("name", equalTo("current"))
            .body("type", equalTo("Microsoft.Sql/servers/databases/transparentDataEncryption"))
            .body("properties.state", equalTo("Enabled"));
        given().when().get(DATABASE + "/securityAlertPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.state", equalTo("Disabled"))
            .body("properties.emailAccountAdmins", equalTo(false))
            .body("properties.retentionDays", equalTo(0));
        given().when().get(DATABASE + "/backupShortTermRetentionPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.retentionDays", equalTo(7))
            .body("properties.diffBackupIntervalInHours", equalTo(12));
        given().when().get(DATABASE + "/backupLongTermRetentionPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.weeklyRetention", equalTo("PT0S"))
            .body("properties.monthlyRetention", equalTo("PT0S"))
            .body("properties.yearlyRetention", equalTo("PT0S"))
            .body("properties.weekOfYear", equalTo(0));
        given().when().get(DATABASE + "/geoBackupPolicies/Default" + API)
            .then().statusCode(200)
            .body("name", equalTo("Default"))
            .body("properties.state", equalTo("Enabled"));
    }

    @Test
    void transparentDataEncryptionStoresItsState() {
        createDatabase();
        given().contentType("application/json")
            .body("{\"properties\":{\"state\":\"Disabled\"}}")
            .when().put(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200)
            .body("id", endsWith("/servers/tfserver/databases/tfdb/transparentDataEncryption/current"))
            .body("properties.state", equalTo("Disabled"));

        given().when().get(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200)
            .body("properties.state", equalTo("Disabled"));
    }

    @Test
    void theSecurityAlertPolicyStoresItsFieldsButNeverReadsBackTheAccessKey() {
        createDatabase();
        given().contentType("application/json")
            .body("{\"properties\":{\"state\":\"Enabled\",\"emailAccountAdmins\":true,"
                + "\"emailAddresses\":[\"ops@example.com\"],\"disabledAlerts\":[\"Sql_Injection\"],"
                + "\"retentionDays\":30,\"storageAccountAccessKey\":\"c2VjcmV0\"}}")
            .when().put(DATABASE + "/securityAlertPolicies/Default" + API)
            .then().statusCode(200)
            .body("name", equalTo("default"));

        given().when().get(DATABASE + "/securityAlertPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.state", equalTo("Enabled"))
            .body("properties.emailAccountAdmins", equalTo(true))
            .body("properties.emailAddresses", contains("ops@example.com"))
            .body("properties.disabledAlerts", contains("Sql_Injection"))
            .body("properties.retentionDays", equalTo(30))
            .body("properties", not(hasKey("storageAccountAccessKey")));
    }

    @Test
    void theShortTermRetentionPolicyIsReplacedByPutAndMergedByPatch() {
        createDatabase();
        given().contentType("application/json")
            .body("{\"properties\":{\"retentionDays\":14,\"diffBackupIntervalInHours\":24}}")
            .when().put(DATABASE + "/backupShortTermRetentionPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.retentionDays", equalTo(14))
            .body("properties.diffBackupIntervalInHours", equalTo(24));

        given().contentType("application/json")
            .body("{\"properties\":{\"retentionDays\":21}}")
            .when().patch(DATABASE + "/backupShortTermRetentionPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.retentionDays", equalTo(21))
            .body("properties.diffBackupIntervalInHours", equalTo(24));
    }

    @Test
    void theLongTermRetentionPolicyStoresItsRetentions() {
        createDatabase();
        given().contentType("application/json")
            .body("{\"properties\":{\"weeklyRetention\":\"P1W\",\"monthlyRetention\":\"PT0S\","
                + "\"yearlyRetention\":\"P5Y\",\"weekOfYear\":5,\"makeBackupsImmutable\":false}}")
            .when().put(DATABASE + "/backupLongTermRetentionPolicies/default" + API)
            .then().statusCode(200);

        given().when().get(DATABASE + "/backupLongTermRetentionPolicies/default" + API)
            .then().statusCode(200)
            .body("properties.weeklyRetention", equalTo("P1W"))
            .body("properties.yearlyRetention", equalTo("P5Y"))
            .body("properties.weekOfYear", equalTo(5))
            .body("properties.makeBackupsImmutable", equalTo(false));
    }

    @Test
    void aPatchOfASettingTheSpecOnlyPutsIsMethodNotAllowed() {
        createDatabase();
        given().contentType("application/json")
            .body("{\"properties\":{\"state\":\"Disabled\"}}")
            .when().patch(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(405)
            .body("error.code", equalTo("MethodNotAllowed"));
    }

    @Test
    void aSettingUnderAnotherNameIsNotFound() {
        createDatabase();
        given().when().get(DATABASE + "/transparentDataEncryption/other" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aSettingOfAnUnknownDatabaseIsNotFound() {
        createServer();
        given().when().get(SERVER + "/databases/ghost/transparentDataEncryption/current" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aRecreatedDatabaseStartsWithDefaultSettings() {
        createDatabase();
        given().contentType("application/json")
            .body("{\"properties\":{\"state\":\"Disabled\"}}")
            .when().put(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200);
        given().when().delete(DATABASE + API).then().statusCode(204);

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"S0\"}}")
            .when().put(DATABASE + API)
            .then().statusCode(201);
        given().when().get(DATABASE + "/transparentDataEncryption/current" + API)
            .then().statusCode(200)
            .body("properties.state", equalTo("Enabled"));
    }

    @Test
    void theListOfADatabaseSettingHoldsTheOne() {
        createDatabase();
        given().when().get(DATABASE + "/securityAlertPolicies" + API)
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo("default"));
    }

    @Test
    void everySettingTypeIsDeclaredOnTheArmSurface() {
        List<String> declared = new SqlArmSurface().armSurfaces().stream()
            .map(ArmSurface::types)
            .flatMap(List::stream)
            .map(ArmResourceType::type)
            .toList();
        for (SqlSettingType type : SqlSettingType.values()) {
            assertThat(declared, hasItem(type.armType()));
        }
        assertThat(declared, hasItem("Microsoft.Sql/servers/restorableDroppedDatabases"));
    }
}
