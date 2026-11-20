# Service Bus under azurerm 4.x, which pins Microsoft.ServiceBus 2024-01-01 and makes several fields
# Optional with defaults it sends and compares, where 3.x (the provider the rest of this suite runs)
# has them Optional+Computed. Kept apart from ../main.tf so the main suite stays on 3.x.

terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

variable "subscription_id" {
  type    = string
  default = "00000000-0000-0000-0000-000000000001"
}

variable "tenant_id" {
  type    = string
  default = "00000000-0000-0000-0000-000000000002"
}

variable "metadata_host" {
  type    = string
  default = "localhost:4577"
}

provider "azurerm" {
  features {}
  use_cli                         = false
  resource_provider_registrations = "none"

  environment   = "stack"
  metadata_host = var.metadata_host

  subscription_id = var.subscription_id
  tenant_id       = var.tenant_id
  client_id       = "11111111-1111-1111-1111-111111111111"
  client_secret   = "floci-az-dev-secret"
}

resource "azurerm_resource_group" "sb4" {
  name     = "floci-test-sb4-rg"
  location = "eastus"
}

resource "azurerm_servicebus_namespace" "sb4" {
  name                = "floci-test-sb4ns"
  location            = azurerm_resource_group.sb4.location
  resource_group_name = azurerm_resource_group.sb4.name
  sku                 = "Standard"
}

resource "azurerm_servicebus_queue" "sb4" {
  name         = "floci-test-sb4-queue"
  namespace_id = azurerm_servicebus_namespace.sb4.id
}

# default_message_ttl defaults to the unbounded P10675199DT2H48M5.4775807S in 4.x.
resource "azurerm_servicebus_topic" "sb4" {
  name         = "floci-test-sb4-topic"
  namespace_id = azurerm_servicebus_namespace.sb4.id
}

# batched_operations_enabled has no default in 4.x, so it is sent false and compared.
resource "azurerm_servicebus_subscription" "sb4" {
  name                = "floci-test-sb4-sub"
  topic_id            = azurerm_servicebus_topic.sb4.id
  max_delivery_count  = 5
  auto_delete_on_idle = "PT1H"
}

# On a Premium namespace azurerm lists the geo-disaster recovery aliases after writing a rule.
resource "azurerm_servicebus_namespace" "sb4_premium" {
  name                         = "floci-test-sb4prem"
  location                     = azurerm_resource_group.sb4.location
  resource_group_name          = azurerm_resource_group.sb4.name
  sku                          = "Premium"
  capacity                     = 1
  premium_messaging_partitions = 1
}

resource "azurerm_servicebus_namespace_authorization_rule" "sb4_premium" {
  name         = "floci-test-sb4-rule"
  namespace_id = azurerm_servicebus_namespace.sb4_premium.id
  listen       = true
  send         = true
}
