variable "environment" {
  description = "Deployment environment (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "location" {
  description = "Azure region"
  type        = string
  default     = "eastus"
}

variable "project" {
  description = "Project name used for resource naming"
  type        = string
  default     = "cicd-platform"
}

variable "image_tag" {
  description = "Immutable image tag (e.g. a commit SHA) deployed to the container apps. CI/CD always passes a commit SHA; 'latest' is never used for production deploys."
  type        = string
  default     = "latest"
}

variable "postgres_user" {
  description = "PostgreSQL administrator username"
  type        = string
  default     = "cicd"
}

variable "postgres_database" {
  description = "PostgreSQL database name created on the flexible server"
  type        = string
  default     = "cicd"
}

variable "tags" {
  description = "Common Azure resource tags"
  type        = map(string)
  default = {
    project    = "cicd-platform"
    managed-by = "terraform"
    owner      = "cicd-platform"
  }
}