output "resource_group_name" {
  description = "Name of the resource group"
  value       = azurerm_resource_group.main.name
}

output "acr_login_server" {
  description = "Azure Container Registry login server"
  value       = ""
  # value = azurerm_container_registry.main.login_server
}

output "postgresql_fqdn" {
  description = "PostgreSQL server fully qualified domain name"
  value       = ""
  # value = azurerm_postgresql_flexible_server.main.fqdn
}
