terraform {
  required_version = ">= 1.5.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.80"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  backend "azurerm" {
    # Remote state storage is configured via -backend-config on init
    # (see environments/dev/dev.tfbackend and docs/azure-terraform.md).
    # For local-only validation use: terraform init -backend=false
  }
}