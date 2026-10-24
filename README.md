# be-cep-api · api · java/spring
Gerado pela plataforma (`create-project`). Contrato mínimo:
`Dockerfile` (targets `dev`/`runner`) · `project.yaml` · `.env.example`.

- **Tipo:** api · **Linguagem:** java · **Framework:** spring
- **Porta interna:** 4000 · **Protocolos:** `http` `stream` 
- **HTTP:** http://localhost:8088/api/be-cep-api (strip de `/api/be-cep-api`) · health `/api/be-cep-api/health`
- **Cloud:** aws (Terraform em `infra/aws`, kind `api`)
- **Recursos (floci-aws):** `postgres` `kafka` 
- **Segredos:** `secret/be-cep-api/<env>` no Vault
- **Observabilidade:** dashboard e alertas em [`observability/`](observability/) · **SLO** 99.9% em 30d (error budget 43min) · p95 alvo 500ms

```bash
make up        # sobe este workload junto da plataforma
curl http://localhost:8088/api/be-cep-api/health
```
## Subir sem a plataforma (`docker-compose.yml`)

Este repo sobe sozinho. O [`docker-compose.yml`](docker-compose.yml) é **standalone**: traz o domínio
deste workload e mais nada — Vault (já semeado com `secret/be-cep-api/dev`) · `floci-aws`, a cloud local · o broker `kafka` · os recursos declarados em `project.yaml`.

```bash
docker compose up            # aqui, na raiz deste repo — sem clonar a plataforma
curl http://localhost:4000/health
```

**Qual compose vale quando:** os dois nunca são carregados juntos, então não há merge nem conflito.
A plataforma monta `docker-compose.base.yml` + [`service.yml`](service.yml) com `-f` explícito
(`compose.gen.mjs`) e **ignora** este arquivo; é o da plataforma que manda no `make up`. Rodando por
lá, o workload ganha Traefik (rota `/api/be-cep-api`), a
observabilidade e os emuladores compartilhados. Aqui, ganha o mínimo para funcionar:

| | plataforma (`make up`) | standalone (`docker compose up`) |
|---|---|---|
| acesso | `http://localhost:8088/api/be-cep-api` (Traefik) | `http://localhost:4000` (porta publicada) |
| métricas | Prometheus raspa o `/metrics` | `/metrics` exposto, ninguém raspa |
| rede | `platform`, compartilhada | própria deste projeto |
| Vault | semeado do `platform.yaml` | semeado aqui pelo serviço `vault-seed` |
| cloud local | os três emuladores da base | só `floci-aws` |
Os recursos usam as **mesmas imagens e versões** da base da plataforma, de propósito: é o que faz o
workload se comportar igual nos dois modos.


## Arquivos gerados e como travar (`configs/.regenerate-lock`)

Parte deste repo é **gerada a partir do [`project.yaml`](project.yaml)** e pode ser reescrita quando
alguém rodar um `make regenerate-*` na plataforma. É assim que uma mudança da plataforma (um recurso
novo em `resources:`, um painel novo no dashboard) chega até aqui sem recriar o workload.

| Área | Arquivos | Comando |
|---|---|---|
| `ci` | `.github/workflows/release.yml` · `deploy.yml` | `make regenerate-ci NAME=be-cep-api` |
| `observability` | `observability/dashboard.json` · `observability/alerts.yaml` | `make regenerate-obs NAME=be-cep-api` |

**Customizou algum deles?** Descomente a área correspondente no [`configs/.regenerate-lock`](configs/.regenerate-lock)
(um por linha). O gerador passa a **pular este workload e avisar**, em vez de sobrescrever em silêncio:

```bash
# configs/.regenerate-lock
observability      # trava dashboard.json e alerts.yaml
```

Para conferir o que está fora de dia sem escrever nada, use `ARGS=--check`; para sobrescrever mesmo
travado, `ARGS=--force` (fica registrado no log).
### SLO
O SLO vive em `observability.slo` no [`project.yaml`](project.yaml) — **é a fonte da verdade**: alimenta
o cartão SLI/SLO/Error Budget do dashboard e os thresholds dos alertas. Hoje: **99.9%
em 30d** (error budget de **43min**), p95 alvo **500ms**.
Mudou o alvo? Edite lá e rode `make regenerate-obs NAME=be-cep-api`.

A disponibilidade é medida no **Traefik** (borda — o que o cliente vê); os quantis p50/p95/p99 vêm
do **`/metrics` deste serviço** (`http_request_duration_seconds`, com label de rota).
## Documentação da API (OpenAPI / Swagger)

O **contrato operacional** já vem documentado:
- **`GET /api/be-cep-api/openapi.json`** — spec OpenAPI 3.1 (piso estático; sempre disponível).
- **`GET /api/be-cep-api/docs`** — Swagger UI (Scalar) — **só dev/interno** (404 em produção).

O spec fica em [`openapi.json`](openapi.json). Documente **as suas rotas** por cima — *design-first* (edite o `openapi.json`) ou, recomendado, *code-first* com a lib nativa do stack:
> **spring:** `springdoc-openapi` (nativo Spring).
