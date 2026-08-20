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
