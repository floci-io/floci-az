terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0"
    }
  }
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
}

variable "environment_name" {
  type = string
}

variable "location" {
  type = string
}

resource "azurerm_resource_group" "rg" {
  name     = "rg-tf-${var.environment_name}"
  location = var.location
  tags = {
    azd-env-name = var.environment_name
  }
}

output "TF_RESOURCE_GROUP" {
  value = azurerm_resource_group.rg.name
}
