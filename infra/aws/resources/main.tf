# Recursos de dados do workload `be-api` na AWS — Terraform SEPARADO do app.
# Estado próprio (ver backend.tf.example). Aplique À PARTE, só quando quiser que a
# plataforma crie estes recursos; NÃO é aplicado pelo deploy do app e um `destroy`
# do app não os afeta. Em dev, o floci provê os equivalentes locais.
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.region
}

# postgres → providers/aws/modules/db (o app consome via DATABASE_URL)
module "postgres" {
  source = "../../../../../providers/aws/modules/db"

  name   = var.name
  env    = var.env
  region = var.region
  engine = "postgres"
}

output "postgres_connection_url" {
  value     = module.postgres.connection_url
  sensitive = true
}

# kafka → providers/aws/modules/kafka (o app consome via KAFKA_BROKERS)
module "kafka" {
  source = "../../../../../providers/aws/modules/kafka"

  name   = var.name
  env    = var.env
  region = var.region
}

output "kafka_connection_url" {
  value     = module.kafka.connection_url
  sensitive = true
}
