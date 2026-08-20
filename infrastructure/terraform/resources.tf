resource "azurerm_resource_group" "main" {
  name     = "rg-${var.project}-${var.environment}"
  location = var.location

  tags = {
    project     = var.project
    environment = var.environment
    managed-by  = "terraform"
  }
}

# --- Placeholder resources for future phases ---
#
# Azure Container Registry (Phase 5: Docker/ACR)
# azurerm_container_registry.main
#
# Azure Database for PostgreSQL (Phase 0/1: database)
# azurerm_postgresql_flexible_server.main
#
# Azure Key Vault (Phase 1: secrets)
# azurerm_key_vault.main
#
# Azure Container Apps (Phase 5: deployment)
# azurerm_container_app_environment.main
# azurerm_container_app.backend
# azurerm_container_app.frontend
# azurerm_container_app.worker
#
# Azure Monitor / Application Insights (Phase 6: observability)
# azurerm_log_analytics_workspace.main
# azurerm_application_insights.main
#
# Azure Storage Account (logs/artifacts)
# azurerm_storage_account.main
