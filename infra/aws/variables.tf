variable "name" {
  type    = string
  default = "be-cep-api"
}

variable "port" {
  type    = number
  default = 4000
}

variable "image" {
  type    = string
  default = "" # ECR URI (api/worker/function) — vazio p/ spa
}

variable "domain" {
  type    = string
  default = "be-cep-api.local"
}

variable "region" {
  type    = string
  default = "sa-east-1"
}

variable "env" {
  type    = string
  default = "prod"
}
