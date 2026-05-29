terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0"
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
  skip_provider_registration = true
  use_cli                    = false

  environment   = "stack"
  metadata_host = var.metadata_host

  subscription_id = var.subscription_id
  tenant_id       = var.tenant_id
  client_id       = "00000000-0000-0000-0000-000000000003"
  client_secret   = "fake-secret"
}
