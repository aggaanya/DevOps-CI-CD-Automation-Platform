output "resource_group_name" {
  description = "Name of the resource group"
  value       = azurerm_resource_group.main.name
}

output "acr_login_server" {
  description = "Azure Container Registry login server (used by CI/CD to push images)"
  value       = azurerm_container_registry.main.login_server
  sensitive   = false
}

output "postgresql_fqdn" {
  description = "PostgreSQL server fully qualified domain name"
  value       = azurerm_postgresql_flexible_server.main.fqdn
}

output "key_vault_name" {
  description = "Key Vault holding the generated PostgreSQL/RabbitMQ secrets"
  value       = azurerm_key_vault.main.name
}

output "key_vault_uri" {
  description = "Key Vault base URI"
  value       = azurerm_key_vault.main.vault_uri
}

output "storage_account_name" {
  description = "Storage account hosting the Azure Files shared volumes"
  value       = azurerm_storage_account.main.name
}

output "user_assigned_identity_id" {
  description = "Resource ID of the app identity (AcrPull + Key Vault reader)"
  value       = azurerm_user_assigned_identity.main.id
}

output "user_assigned_identity_client_id" {
  description = "Client ID of the app identity (used by CAE registry/secret auth)"
  value       = azurerm_user_assigned_identity.main.client_id
  sensitive   = true
}

output "container_app_environment_id" {
  description = "Container Apps Environment resource ID"
  value       = azurerm_container_app_environment.main.id
}

output "frontend_fqdn" {
  description = "Public URL of the frontend dashboard"
  value       = azurerm_container_app.frontend.ingress[0].fqdn
}

output "backend_fqdn" {
  description = "Internal FQDN of the backend control plane"
  value       = azurerm_container_app.backend.ingress[0].fqdn
}

output "worker_fqdn" {
  description = "Internal FQDN of the worker"
  value       = azurerm_container_app.worker.ingress[0].fqdn
}

output "rabbitmq_fqdn" {
  description = "Internal FQDN of RabbitMQ"
  value       = azurerm_container_app.rabbitmq.ingress[0].fqdn
}