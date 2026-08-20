terraform {
  required_version = ">= 1.5.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.80"
    }
  }

  backend "azurerm" {
    # Configure for your environment:
    # resource_group_name  = "rg-cicd-terraform-state"
    # storage_account_name = "stcicdterraformstate"
    # container_name       = "tfstate"
    # key                  = "dev.tfstate"
  }
}

provider "azurerm" {
  features {}
}
