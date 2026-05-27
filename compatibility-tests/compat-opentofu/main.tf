variable "location" {
  type    = string
  default = "eastus"
}

resource "azurerm_resource_group" "rg" {
  name     = "floci-test-rg"
  location = var.location
}

resource "azurerm_storage_account" "sa" {
  name                     = "flocitestsa"
  resource_group_name      = azurerm_resource_group.rg.name
  location                 = azurerm_resource_group.rg.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
}

resource "azurerm_storage_container" "sc" {
  name                 = "floci-test-container"
  storage_account_name = azurerm_storage_account.sa.name
}

resource "azurerm_storage_queue" "sq" {
  name                 = "floci-test-queue"
  storage_account_name = azurerm_storage_account.sa.name
}

resource "azurerm_key_vault" "kv" {
  name                = "floci-test-kv"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tenant_id           = var.tenant_id
  sku_name            = "standard"

  soft_delete_retention_days = 7
  purge_protection_enabled   = false
}

resource "azurerm_key_vault_secret" "secret" {
  name         = "floci-test-secret"
  value        = "hello-from-opentofu"
  key_vault_id = azurerm_key_vault.kv.id
}

output "storage_account_name" {
  value = azurerm_storage_account.sa.name
}

output "key_vault_name" {
  value = azurerm_key_vault.kv.name
}

output "key_vault_uri" {
  value = azurerm_key_vault.kv.vault_uri
}
