package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ServiceBusTopologyLoaderUnitTest {

    @TempDir
    Path tempDir;

    @Test
    void failedNamespaceIsSkippedWithoutReusingConfiguredPorts() throws Exception {
        Path topologyFile = tempDir.resolve("Config.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [
                      { "Name": "failed", "Queues": [{ "Name": "orphan" }] },
                      { "Name": "working" }
                    ]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(false);
        when(serviceBus.amqpPort()).thenReturn(5672);
        when(serviceBus.amqpTlsPort()).thenReturn(5671);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(namespaceManager.startNamespace("failed", 5672, 5671))
                .thenThrow(new ServiceBusNamespaceManager.NamespaceStartException(
                        "failed", false, new IllegalStateException("Docker unavailable")));

        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(namespaceManager).startNamespace("failed", 5672, 5671);
        verify(namespaceManager).startNamespace("working", 0, 0);
        verify(namespaceManager, never()).startNamespace("working", 5672, 5671);
        verifyNoInteractions(handler);
    }

    @Test
    void cleanedFailedNamespaceReusesConfiguredPorts() throws Exception {
        Path topologyFile = tempDir.resolve("cleaned.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [
                      { "Name": "failed" },
                      { "Name": "working" }
                    ]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(false);
        when(serviceBus.amqpPort()).thenReturn(5672);
        when(serviceBus.amqpTlsPort()).thenReturn(5671);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(namespaceManager.startNamespace("failed", 5672, 5671))
                .thenThrow(new ServiceBusNamespaceManager.NamespaceStartException(
                        "failed", true, new IllegalStateException("Readiness failed")));

        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(namespaceManager).startNamespace("failed", 5672, 5671);
        verify(namespaceManager).startNamespace("working", 5672, 5671);
        verify(namespaceManager, never()).startNamespace("working", 0, 0);
        verifyNoInteractions(handler);
    }

    @Test
    void rejectedRenamePreservesExistingRule() throws Exception {
        Path topologyFile = tempDir.resolve("replacement.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [{
                      "Name": "namespace",
                      "Topics": [{
                        "Name": "topic",
                        "Subscriptions": [{
                          "Name": "subscription",
                          "Rules": [{
                            "Name": "replacement",
                            "Properties": {
                              "FilterType": "Sql",
                              "SqlFilter": { "SqlExpression": "priority % 2 = 0" }
                            }
                          }]
                        }]
                      }]
                    }]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(true);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(handler.handleCreateTopic(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), any(), any()))
                .thenReturn(Response.status(201).build());
        when(handler.handleCreateSubscription(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                eq(false), any(ServiceBusEntityXml.MessageLifetimeSettings.class),
                any(ServiceBusEntityXml.DeliverySettings.class),
                any(ServiceBusModels.RuleEntity.class)))
                .thenReturn(Response.ok().build());

        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(handler, never()).replaceRules(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"), any());
    }

    @Test
    void newSubscriptionStartsWithOnlyValidDeclaredRule() throws Exception {
        Path topologyFile = tempDir.resolve("partial-replacement.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [{
                      "Name": "namespace",
                      "Topics": [{
                        "Name": "topic",
                        "Subscriptions": [{
                          "Name": "subscription",
                          "Rules": [
                            {
                              "Name": "valid",
                              "Properties": {
                                "FilterType": "Sql",
                                "SqlFilter": { "SqlExpression": "priority > 2" }
                              }
                            },
                            {
                              "Name": "$Default",
                              "Properties": {
                                "FilterType": "Sql",
                                "SqlFilter": { "SqlExpression": "priority % 2 = 0" }
                              }
                            }
                          ]
                        }]
                      }]
                    }]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(true);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(handler.handleCreateTopic(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), any(), any()))
                .thenReturn(Response.status(201).build());
        when(handler.handleCreateSubscription(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                eq(false), any(ServiceBusEntityXml.MessageLifetimeSettings.class),
                any(ServiceBusEntityXml.DeliverySettings.class),
                any(ServiceBusModels.RuleEntity.class)))
                .thenReturn(Response.status(201).build());
        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(handler).handleCreateSubscription(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                eq(false), any(ServiceBusEntityXml.MessageLifetimeSettings.class),
                any(ServiceBusEntityXml.DeliverySettings.class),
                argThat(rule -> "valid".equals(rule.name())));
        verify(handler, never()).replaceRules(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"), any());
    }

    @Test
    void newSubscriptionWithOnlyRejectedRulesKeepsDefault() throws Exception {
        Path topologyFile = tempDir.resolve("rejected-rules.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [{
                      "Name": "namespace",
                      "Topics": [{
                        "Name": "topic",
                        "Subscriptions": [{
                          "Name": "subscription",
                          "Rules": [{
                            "Name": "invalid",
                            "Properties": {
                              "FilterType": "Sql",
                              "SqlFilter": { "SqlExpression": "priority % 2 = 0" }
                            }
                          }]
                        }]
                      }]
                    }]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(true);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(handler.handleCreateTopic(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), any(), any()))
                .thenReturn(Response.status(201).build());
        when(handler.handleCreateSubscription(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                eq(false), any(ServiceBusEntityXml.MessageLifetimeSettings.class),
                any(ServiceBusEntityXml.DeliverySettings.class),
                any(ServiceBusModels.RuleEntity.class)))
                .thenReturn(Response.status(201).build());

        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(handler, never()).replaceRules(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"), any());
    }

    @Test
    void failedRuleReplacementStartsWithRestrictiveDeclaredRule() throws Exception {
        Path topologyFile = tempDir.resolve("failed-replacement.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [{
                      "Name": "namespace",
                      "Topics": [{
                        "Name": "topic",
                        "Subscriptions": [{
                          "Name": "subscription",
                          "Rules": [
                            {
                              "Name": "first",
                              "Properties": {
                                "FilterType": "Sql",
                                "SqlFilter": { "SqlExpression": "priority > 2" }
                              }
                            },
                            {
                              "Name": "second",
                              "Properties": {
                                "FilterType": "Sql",
                                "SqlFilter": { "SqlExpression": "region = 'eu'" }
                              }
                            }
                          ]
                        }]
                      }]
                    }]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(true);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(handler.handleCreateTopic(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), any(), any()))
                .thenReturn(Response.status(201).build());
        when(handler.handleCreateSubscription(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                eq(false), any(ServiceBusEntityXml.MessageLifetimeSettings.class),
                any(ServiceBusEntityXml.DeliverySettings.class),
                any(ServiceBusModels.RuleEntity.class)))
                .thenReturn(Response.status(201).build());
        when(handler.replaceRules(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"), any()))
                .thenReturn(Response.serverError().build());

        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(handler).handleCreateSubscription(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                eq(false), any(ServiceBusEntityXml.MessageLifetimeSettings.class),
                any(ServiceBusEntityXml.DeliverySettings.class),
                argThat(rule -> "first".equals(rule.name())
                        && "priority > 2".equals(rule.sqlExpression())));
        verify(handler).replaceRules(
                eq("devstoreaccount1"), eq("namespace"), eq("topic"), eq("subscription"),
                argThat(rules -> rules.size() == 2));
    }
}
