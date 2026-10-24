# Terraform do workload `be-cep-api` na AWS. GERADO pela plataforma.
# A INTENÇÃO (project.yaml: kind=api) escolhe o módulo: tfKind=api.
#   api → ECS Fargate + ALB   ·   spa → S3 + CloudFront
# ► cloud PRIMÁRIA deste workload.
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.region
}

module "be-cep-api" {
  source = "../../../../providers/aws/modules/api"

  name   = var.name
  port   = var.port
  image  = var.image
  domain = var.domain
  env    = var.env
}

output "endpoint" {
  value = module.be-cep-api.endpoint
}
# NOTA: recursos de dados (banco/fila/cache) NÃO nascem aqui — ficam em ./resources/
# (Terraform à parte, estado próprio). Aplique só se quiser que a plataforma os crie;
# um `destroy` deste app não os afeta.
