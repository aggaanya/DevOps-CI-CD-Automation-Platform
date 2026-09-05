data "azurerm_client_config" "current" {}

# Stable 6-hex suffix for globally-unique names (mirrors the persisted suffix
# used by scripts/azure/config.ps1). Keepers make it stable across applies but
# force a fresh suffix when project or environment changes.
resource "random_id" "suffix" {
  byte_length = 3
  keepers = {
    project     = var.project
    environment = var.environment
  }
}

locals {
  resource_group_name = "rg-${var.project}-${var.environment}"

  # Zero-downtime friendly helpers
  cae_name        = "cae-cicd-${var.environment}"
  vnet_name       = "cicd-vnet-${var.environment}"
  app_subnet_name = "app-subnet"
  pg_subnet_name  = "pg-subnet"
  uai_name        = "id-cicd-${var.environment}"

  # These names must be globally unique; the hex suffix guarantees it.
  suffix          = random_id.suffix.hex
  acr_name        = "cicdacr${local.suffix}"
  storage_name    = "cicdstore${local.suffix}"
  key_vault_name  = "kv-cicd${local.suffix}"
  log_analytics   = "la-cicd${local.suffix}"
  postgresql_name = "pg-cicd-${local.suffix}"
  postgresql_fqdn = "${local.postgresql_name}.postgres.database.azure.com"

  # Image references — CI/CD injects a commit SHA via var.image_tag.
  backend_image  = "${azurerm_container_registry.main.login_server}/cicd-backend:${var.image_tag}"
  worker_image   = "${azurerm_container_registry.main.login_server}/cicd-worker:${var.image_tag}"
  frontend_image = "${azurerm_container_registry.main.login_server}/cicd-frontend:${var.image_tag}"

  # Spring datasource string used by the control plane (ssl required).
  pg_url = "jdbc:postgresql://${local.postgresql_fqdn}:5432/${var.postgres_database}?sslmode=require"
}