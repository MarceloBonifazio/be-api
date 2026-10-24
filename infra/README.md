# Infra do workload `be-cep-api` (Terraform)

Gerado para a cloud selecionada: **aws** (declarada no `project.yaml`).
Referencia o módulo do provider para o `kind=api` (tfKind=`api`).

```
infra/aws/     → providers/aws/modules/api
```

Em dev a cloud é emulada pelo **floci**; aqui é o provisionamento de **produção**.

```bash
cd infra/aws
terraform init
terraform plan -var="image=<registry>/be-cep-api:<tag>"
```

Só a cloud selecionada é gerada (sem infra morta). Para **portar para outra cloud**
(remove a atual e gera a nova — a intenção no `project.yaml` não muda, só o adapter):

```bash
make switch-cloud NAME=be-cep-api CLOUD=<aws|gcp|azure>
```

## Recursos de dados (banco/fila/cache) — Terraform SEPARADO

Este workload declara recursos (`postgres`, `kafka`). Eles **não** nascem
com o app: ficam em `infra/aws/resources/` com **estado próprio** e ciclo de vida
independente. O deploy do app (`main.tf`) só cria o compute; um `terraform destroy` do
app **não** afeta os recursos.

Aplique-os À PARTE, e só quando quiser que a plataforma os crie (se o banco/fila for
externo/compartilhado, simplesmente não aplique — o app consome o endpoint via Vault):

```bash
cd infra/aws/resources
terraform init
terraform apply           # cria RDS/Cloud SQL, ElastiCache, SQS/PubSub/Service Bus, etc.
terraform output          # connection_url (sensitive) → fiar no secret do workload no Vault
```

Em dev, o **floci** provê os equivalentes locais (nada disto é aplicado).
