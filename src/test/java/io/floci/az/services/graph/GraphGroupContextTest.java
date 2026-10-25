package io.floci.az.services.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.services.entra.DirectoryEntitySet;
import io.floci.az.services.entra.DirectoryReader;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.floci.az.services.entra.TenantRef;
import io.floci.az.services.entra.TenantResolver;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphGroupContextTest {

    @ParameterizedTest
    @CsvSource({
            "AzureCloud, https://graph.microsoft.com",
            "AzureUSGovernment, https://graph.microsoft.us",
            "AzureChinaCloud, https://microsoftgraph.chinacloudapi.cn"
    })
    void groupContextUsesTheDirectoryTenantsCloud(String cloud, String endpoint) throws Exception {
        String tenantId = "directory-tenant";
        TenantResolver tenants = mock(TenantResolver.class);
        when(tenants.defaultTenant()).thenReturn(new TenantRef("token-tenant", tenantId, true));
        AzureDirectory azureDirectory = mock(AzureDirectory.class);
        when(azureDirectory.cloudOfTenant(tenantId)).thenReturn(CloudCatalog.forNameOrPublic(cloud));
        GraphDirectoryStore store = mock(GraphDirectoryStore.class);
        when(store.list(tenantId, DirectoryEntitySet.GROUPS)).thenReturn(List.of());
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.EntraConfig entra = mock(EmulatorConfig.EntraConfig.class);
        when(config.services()).thenReturn(services);
        when(services.entra()).thenReturn(entra);
        when(entra.defaultTenantId()).thenReturn(tenantId);
        GraphServiceHandler handler = new GraphServiceHandler(config, store,
                mock(DirectoryReader.class), tenants, azureDirectory);

        for (boolean expand : List.of(false, true)) {
            Map<String, String> query = expand ? Map.of("$expand", "members") : Map.of();
            AzureRequest request = new AzureRequest("GET", null, "graph", "v1.0/groups",
                    null, null, query, null, false);
            try (Response response = handler.handle(request)) {
                assertEquals(200, response.getStatus());
                JsonNode body = new ObjectMapper().readTree((String) response.getEntity());
                assertEquals(endpoint + "/v1.0/$metadata#groups" + (expand ? "(members())" : ""),
                        body.path("@odata.context").asText());
            }
        }
    }
}
