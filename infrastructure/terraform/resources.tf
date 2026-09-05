# =============================================================================
# Resource group
# =============================================================================
resource "azurerm_resource_group" "main" {
  name     = local.resource_group_name
  location = var.location

  tags = merge(var.tags, {
    environment = var.environment
  })
}

# =============================================================================
# Observability: Log Analytics workspace (Container Apps logs sink)
# =============================================================================
resource "azurerm_log_analytics_workspace" "main" {
  name                = local.log_analytics
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = 30

  tags = var.tags
}

# =============================================================================
# Networking: VNet + delegated subnets for CAE and PostgreSQL
# =============================================================================
resource "azurerm_virtual_network" "main" {
  name                = local.vnet_name
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  address_space       = ["10.1.0.0/16"]

  tags = var.tags
}

resource "azurerm_subnet" "app" {
  name                 = local.app_subnet_name
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.1.0.0/22"]

  delegation {
    name = "microsoft-app-environments"

    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "postgres" {
  name                 = local.pg_subnet_name
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.1.4.0/24"]

  delegation {
    name = "microsoft-dbforpostgresql-flexibleservers"

    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# =============================================================================
# State / durable volumes: Storage account + Azure Files shares
# =============================================================================
resource "azurerm_storage_account" "main" {
  name                            = local.storage_name
  resource_group_name             = azurerm_resource_group.main.name
  location                        = azurerm_resource_group.main.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  account_kind                    = "StorageV2"
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false

  tags = var.tags
}

resource "azurerm_storage_share" "rabbitmq" {
  name                 = "rabbitmq-data"
  storage_account_name = azurerm_storage_account.main.name
  quota                = 20
}

resource "azurerm_storage_share" "worker" {
  name                 = "worker-workspaces"
  storage_account_name = azurerm_storage_account.main.name
  quota                = 20
}

# =============================================================================
# Identity + secrets: User-assigned identity, Key Vault, generated secrets
# =============================================================================
resource "azurerm_user_assigned_identity" "main" {
  name                = local.uai_name
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location

  tags = var.tags
}

resource "azurerm_key_vault" "main" {
  name                       = local.key_vault_name
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  soft_delete_retention_days = 7

  tags = var.tags
}

# Allow the deployment caller (OIDC/CI, or a human) to read the generated
# secrets when wiring up integration tests. The CI principal is granted this
# policy via the bootstrap step / documentation (docs/azure-terraform.md).
resource "azurerm_key_vault_access_policy" "deployer" {
  key_vault_id = azurerm_key_vault.main.id
  tenant_id    = data.azurerm_client_config.current.tenant_id
  object_id    = data.azurerm_client_config.current.object_id

  secret_permissions = ["Get", "List"]
}

# The UAI must be able to read Key Vault secrets referenced by CAE secretRefs.
resource "azurerm_key_vault_access_policy" "apps" {
  key_vault_id = azurerm_key_vault.main.id
  tenant_id    = azurerm_user_assigned_identity.main.tenant_id
  object_id    = azurerm_user_assigned_identity.main.principal_id

  secret_permissions = ["Get", "List"]
}

resource "random_password" "postgres" {
  length           = 24
  upper            = true
  lower            = true
  numeric          = true
  special          = true
  min_upper        = 6
  min_lower        = 6
  min_numeric      = 6
  min_special      = 4
  override_special = "!@#%^*-_=+"
}

resource "random_password" "rabbit" {
  length           = 24
  upper            = true
  lower            = true
  numeric          = true
  special          = true
  min_upper        = 6
  min_lower        = 6
  min_numeric      = 6
  min_special      = 4
  override_special = "!@#%^*-_=+"
}

resource "azurerm_key_vault_secret" "pg_password" {
  name         = "cicd-pg-password"
  value        = random_password.postgres.result
  key_vault_id = azurerm_key_vault.main.id
}

resource "azurerm_key_vault_secret" "rabbit_user" {
  name         = "cicd-rabbit-user"
  value        = "cicd"
  key_vault_id = azurerm_key_vault.main.id
}

resource "azurerm_key_vault_secret" "rabbit_pass" {
  name         = "cicd-rabbit-pass"
  value        = random_password.rabbit.result
  key_vault_id = azurerm_key_vault.main.id
}

# =============================================================================
# Container Registry (admin account stays disabled; apps pull via UAI AcrPull)
# =============================================================================
resource "azurerm_container_registry" "main" {
  name                = local.acr_name
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Basic"
  admin_enabled       = false

  tags = var.tags
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.main.principal_id
}

# =============================================================================
# Database: Azure PostgreSQL Flexible Server (private, delegated subnet)
# =============================================================================
resource "azurerm_private_dns_zone" "postgres" {
  name                = local.postgresql_fqdn
  resource_group_name = azurerm_resource_group.main.name
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                  = "pg-cicd-link"
  private_dns_zone_name = azurerm_private_dns_zone.postgres.name
  resource_group_name   = azurerm_resource_group.main.name
  virtual_network_id    = azurerm_virtual_network.main.id
}

resource "azurerm_postgresql_flexible_server" "main" {
  name                   = local.postgresql_name
  resource_group_name    = azurerm_resource_group.main.name
  location               = azurerm_resource_group.main.location
  administrator_login    = var.postgres_user
  administrator_password = random_password.postgres.result
  version                = "16"
  sku_name               = "B_Standard_B1ms"
  storage_mb             = 32768
  delegated_subnet_id    = azurerm_subnet.postgres.id
  private_dns_zone_id    = azurerm_private_dns_zone.postgres.id

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]

  tags = var.tags
}

resource "azurerm_postgresql_flexible_server_database" "main" {
  name      = var.postgres_database
  server_id = azurerm_postgresql_flexible_server.main.id
}

# =============================================================================
# Container Apps Environment (internal, on delegated app subnet)
# =============================================================================
resource "azurerm_container_app_environment" "main" {
  name                           = local.cae_name
  resource_group_name            = azurerm_resource_group.main.name
  location                       = azurerm_resource_group.main.location
  infrastructure_subnet_id       = azurerm_subnet.app.id
  internal_load_balancer_enabled = true
  log_analytics_workspace_id     = azurerm_log_analytics_workspace.main.id

  tags = var.tags
}

# Azure Files mounts registered on the CAE (used by rabbitmq + worker apps).
resource "azurerm_container_app_environment_storage" "rabbitmq" {
  name                         = "rabbitmq-data"
  container_app_environment_id = azurerm_container_app_environment.main.id
  account_name                 = azurerm_storage_account.main.name
  access_key                   = azurerm_storage_account.main.primary_access_key
  share_name                   = azurerm_storage_share.rabbitmq.name
  access_mode                  = "ReadWrite"
}

resource "azurerm_container_app_environment_storage" "worker" {
  name                         = "worker-workspaces"
  container_app_environment_id = azurerm_container_app_environment.main.id
  account_name                 = azurerm_storage_account.main.name
  access_key                   = azurerm_storage_account.main.primary_access_key
  share_name                   = azurerm_storage_share.worker.name
  access_mode                  = "ReadWrite"
}

# =============================================================================
# Container Apps
# =============================================================================
resource "azurerm_container_app" "rabbitmq" {
  name                         = "rabbitmq"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }

  secret {
    name                = "rabbit-user"
    key_vault_secret_id = azurerm_key_vault_secret.rabbit_user.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  secret {
    name                = "rabbit-pass"
    key_vault_secret_id = azurerm_key_vault_secret.rabbit_pass.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  ingress {
    external_enabled           = false
    target_port                = 15672
    transport                  = "http"
    allow_insecure_connections = false
    exposed_port               = 5672

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    container {
      name   = "rabbitmq"
      image  = "rabbitmq:3.13-management-alpine"
      cpu    = 0.5
      memory = "1Gi"

      env {
        name        = "RABBITMQ_DEFAULT_USER"
        secret_name = "rabbit-user"
      }
      env {
        name        = "RABBITMQ_DEFAULT_PASS"
        secret_name = "rabbit-pass"
      }

      volume_mounts {
        name = "rabbitmq-data"
        path = "/var/lib/rabbitmq"
      }

      liveness_probe {
        transport               = "TCP"
        port                    = 5672
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    volume {
      name         = "rabbitmq-data"
      storage_type = "AzureFile"
      storage_name = "rabbitmq-data"
    }

    min_replicas = 1
    max_replicas = 1
  }

  tags = var.tags
}

resource "azurerm_container_app" "backend" {
  name                         = "backend"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }

  registry {
    server   = azurerm_container_registry.main.login_server
    identity = azurerm_user_assigned_identity.main.client_id
  }

  secret {
    name                = "pg-password"
    key_vault_secret_id = azurerm_key_vault_secret.pg_password.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  secret {
    name                = "rabbit-user"
    key_vault_secret_id = azurerm_key_vault_secret.rabbit_user.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  secret {
    name                = "rabbit-pass"
    key_vault_secret_id = azurerm_key_vault_secret.rabbit_pass.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  ingress {
    external_enabled           = false
    target_port                = 8081
    transport                  = "http"
    allow_insecure_connections = true

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    container {
      name   = "backend"
      image  = local.backend_image
      cpu    = 1.0
      memory = "2Gi"

      env {
        name  = "SERVER_PORT"
        value = "8081"
      }
      env {
        name  = "POSTGRES_URL"
        value = local.pg_url
      }
      env {
        name  = "POSTGRES_USER"
        value = var.postgres_user
      }
      env {
        name        = "POSTGRES_PASSWORD"
        secret_name = "pg-password"
      }
      env {
        name  = "RABBITMQ_HOST"
        value = azurerm_container_app.rabbitmq.ingress[0].fqdn
      }
      env {
        name  = "RABBITMQ_PORT"
        value = "5672"
      }
      env {
        name        = "RABBITMQ_USERNAME"
        secret_name = "rabbit-user"
      }
      env {
        name        = "RABBITMQ_PASSWORD"
        secret_name = "rabbit-pass"
      }
      env {
        name  = "SPRING_JPA_HIBERNATE_DDL_AUTO"
        value = "validate"
      }
      env {
        # The browser never calls the backend directly (frontend nginx proxies
        # /api/), so CORS uses the derived public frontend origin rather than a
        # cross-app FQDN reference (which would create a Terraform dependency
        # cycle between frontend and backend).
        name  = "APP_CORS_ALLOWED_ORIGINS"
        value = "https://frontend.${azurerm_container_app_environment.main.default_domain}"
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = 8081
        path                    = "/actuator/health"
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    min_replicas = 1
    max_replicas = 1
  }

  depends_on = [azurerm_container_app.rabbitmq]

  tags = var.tags
}

resource "azurerm_container_app" "worker" {
  name                         = "worker"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }

  registry {
    server   = azurerm_container_registry.main.login_server
    identity = azurerm_user_assigned_identity.main.client_id
  }

  secret {
    name                = "rabbit-user"
    key_vault_secret_id = azurerm_key_vault_secret.rabbit_user.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  secret {
    name                = "rabbit-pass"
    key_vault_secret_id = azurerm_key_vault_secret.rabbit_pass.versionless_id
    identity            = azurerm_user_assigned_identity.main.client_id
  }

  ingress {
    external_enabled           = false
    target_port                = 8080
    transport                  = "http"
    allow_insecure_connections = true

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    container {
      name   = "worker"
      image  = local.worker_image
      cpu    = 1.0
      memory = "2Gi"

      env {
        name  = "RABBITMQ_HOST"
        value = azurerm_container_app.rabbitmq.ingress[0].fqdn
      }
      env {
        name  = "RABBITMQ_PORT"
        value = "5672"
      }
      env {
        name        = "RABBITMQ_USERNAME"
        secret_name = "rabbit-user"
      }
      env {
        name        = "RABBITMQ_PASSWORD"
        secret_name = "rabbit-pass"
      }
      env {
        name  = "WORKER_ID"
        value = "worker-azure"
      }
      env {
        name  = "WORKSPACE_ROOT"
        value = "/data/workspaces"
      }
      env {
        name  = "WORKER_MAX_CONCURRENCY"
        value = "2"
      }
      env {
        name  = "WORKER_SANDBOX"
        value = "process"
      }
      env {
        name  = "WORKER_COMMAND_POLICY"
        value = "STRICT"
      }
      env {
        name  = "WORKER_BUILD_IMAGE_ENABLED"
        value = "false"
      }
      env {
        name  = "WORKER_COMMAND_TIMEOUT_MS"
        value = "900000"
      }
      env {
        name  = "WORKER_MAX_PIPELINE_DURATION_MS"
        value = "1800000"
      }
      env {
        name  = "WORKER_RETRY_ENABLED"
        value = "true"
      }
      env {
        name  = "WORKER_MAX_RETRIES"
        value = "3"
      }
      env {
        name  = "WORKER_RETRY_DELAY_MS"
        value = "30000"
      }
      env {
        name  = "CICD_JOBS_EXCHANGE"
        value = "cicd.jobs.exchange"
      }
      env {
        name  = "CICD_RESULTS_EXCHANGE"
        value = "cicd.results.exchange"
      }
      env {
        name  = "CICD_JOB_ROUTING_KEY"
        value = "cicd.job.submitted"
      }
      env {
        name  = "CICD_DELAY_ROUTING_KEY"
        value = "cicd.job.delay"
      }
      env {
        name  = "CICD_DEAD_ROUTING_KEY"
        value = "cicd.job.dead"
      }
      env {
        name  = "CICD_RESULT_ROUTING_KEY"
        value = "cicd.result"
      }
      env {
        name  = "CICD_JOB_QUEUE"
        value = "cicd.jobs"
      }
      env {
        name  = "CICD_DELAY_QUEUE"
        value = "cicd.jobs.delay"
      }
      env {
        name  = "CICD_DLQ"
        value = "cicd.jobs.dlq"
      }

      volume_mounts {
        name = "worker-workspaces"
        path = "/data/workspaces"
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = 8080
        path                    = "/actuator/health"
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    volume {
      name         = "worker-workspaces"
      storage_type = "AzureFile"
      storage_name = "worker-workspaces"
    }

    min_replicas = 1
    max_replicas = 1
  }

  depends_on = [azurerm_container_app.rabbitmq]

  tags = var.tags
}

resource "azurerm_container_app" "frontend" {
  name                         = "frontend"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.main.id]
  }

  registry {
    server   = azurerm_container_registry.main.login_server
    identity = azurerm_user_assigned_identity.main.client_id
  }

  ingress {
    external_enabled           = true
    target_port                = 80
    transport                  = "http"
    allow_insecure_connections = false

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    container {
      name   = "frontend"
      image  = local.frontend_image
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "BACKEND_INTERNAL_HOST"
        value = azurerm_container_app.backend.ingress[0].fqdn
      }

      liveness_probe {
        transport               = "HTTP"
        port                    = 80
        path                    = "/"
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }
    }

    min_replicas = 1
    max_replicas = 2
  }

  depends_on = [azurerm_container_app.backend]

  tags = var.tags
}