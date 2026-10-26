# score — gestão de clientes

Java 21 · Spring Boot 4.1 · Gradle Wrapper · PostgreSQL/JPA/Flyway · Kafka · OAuth2/JWT.

## Requisitos

- Docker Desktop/Engine com Compose v2;
- Git e acesso às imagens Docker;
- 6 GB de memória disponíveis para PostgreSQL, Kafka/Redpanda, Keycloak, WireMock e as aplicações.

## Executar

Docker Compose é suficiente para rodar a solução completa. A partir deste diretório, com `../fe-painel` presente:

```sh
docker compose up --build -d
```

Acesse **http://localhost:8088/painel/**. Login local: `admin` / `admin-dev` (USER + ADMIN) ou `user` / `user-dev` (somente USER). O primeiro boot baixa dependências e pode levar alguns minutos. Se a plataforma já estiver usando a porta 8088, use a integração Nucleus em vez de subir a stack standalone.

Na raiz do Nucleus:

```sh
docker compose -f docker-compose.base.yml -f projects/be-api/service.yml -f projects/fe-painel/service.yml up --build -d
```

Para parar a stack standalone: `docker compose down`. Os volumes preservam os dados. As configurações de identidade são importadas apenas no primeiro boot do realm; mudanças no arquivo não substituem automaticamente um realm existente.

## Arquitetura

Um serviço de clientes com camadas HTTP, aplicação, JPA e adaptadores Kafka/Score. Keycloak é o servidor de identidade; a API é Resource Server JWT. Decisões e limites estão em `../../docs/customers-architecture.md` no orquestrador. O frontend é uma SPA separada e o Score é simulado por WireMock.

POST grava cliente e evento na mesma transação (outbox). Um publicador periódico envia os eventos pendentes. O consumidor altera status e registra `eventId` na mesma transação, tolerando duplicatas. O sistema usa entrega pelo menos uma vez, sem transação distribuída.

## Autenticação e autorização

No painel, o login redireciona ao Keycloak usando Authorization Code + PKCE S256. O token fica em memória. A API exige assinatura válida, emissor, expiração e audiência `be-api` nas rotas `/customers`. USER consulta; ADMIN também escreve. As rotas técnicas `/health`, `/live`, `/version`, `/docs`, `/openapi.json` e `/actuator/**` são públicas.

Para Postman/Insomnia configure OAuth 2.0 Authorization Code com PKCE:

- Authorization URL: `http://localhost:8088/auth/realms/nucleus/protocol/openid-connect/auth`
- Token URL: `http://localhost:8088/auth/realms/nucleus/protocol/openid-connect/token`
- Client ID: `fe-painel`; sem client secret; PKCE S256; escopo `openid`.
- Cadastre o callback exato da sua ferramenta no cliente do Keycloak pelo console `http://localhost:8088/auth/admin/` (operador local `operator` / `operator-dev`). Os callbacks iniciais permitem apenas o painel.

Use o Access Token em `Authorization: Bearer <token>`. Não use ID Token. Fluxo de senha direta está desabilitado.

## Scalar e OpenAPI

O contrato está em `openapi.json` e é servido em `/api/be-api/openapi.json`. A interface interativa Scalar está em http://localhost:8088/api/be-api/docs. No Scalar, use **Authorize** com `Bearer <access-token>` para testar as rotas do domínio.

## Endpoints

Base: `http://localhost:8088/api/be-api`.

| Método | Caminho | Perfil |
|---|---|---|
| POST | `/customers` | ADMIN |
| PUT | `/customers/{id}` | ADMIN |
| DELETE | `/customers/{id}` | ADMIN |
| GET | `/customers/{id}` | USER/ADMIN |
| GET | `/customers?page=0&size=20&status=ACTIVE` | USER/ADMIN |
| GET | `/customers/search?name=joao&page=0&size=20` | USER/ADMIN |
| GET | `/customers/{id}/score` | USER/ADMIN |
| GET | `/openapi.json`, `/docs`, `/version`, `/actuator/prometheus` | público |

CPF: 11 dígitos, sem pontuação, com dígitos verificadores válidos. CPF é único; e-mail não é único. Status: ACTIVE ou INACTIVE. Busca por nome ignora maiúsculas/minúsculas. Listagens são paginadas, com tamanho máximo 100 e ordenadas por ID decrescente.

```sh
export TOKEN='<access-token>'
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   -d '{"name":"João da Silva","cpf":"52998224725","email":"joao@example.com","status":"ACTIVE"}'   http://localhost:8088/api/be-api/customers
curl -H "Authorization: Bearer $TOKEN" http://localhost:8088/api/be-api/customers/1/score
```

Erros: 400 dados inválidos, 401 token ausente/inválido, 403 perfil insuficiente, 404 cliente/Score inexistente, 409 CPF duplicado/conflito de atualização. Score: 502 resposta inválida/falha HTTP, 503 conexão indisponível, 504 timeout. Respostas de validação usam Problem Details e erros por campo.

## Kafka

Tópicos locais com 3 partições:

- `be-api.events`: CUSTOMER_CREATED, com eventId, eventType, customerId e createdAt; chave = customerId.
- `be-api.status-commands`: CUSTOMER_STATUS_CHANGE.
- `be-api.status-commands.DLT`: mensagens inválidas ou com tentativas esgotadas.

No diretório deste projeto, publique um comando na stack standalone:

```sh
printf '%s\n' '{"eventId":"cbca5352-22ad-48f2-aaf2-704735bc7737","eventType":"CUSTOMER_STATUS_CHANGE","customerId":1,"status":"INACTIVE"}' | docker compose exec -T redpanda rpk topic produce be-api.status-commands -k 1
```

No Nucleus, execute o mesmo comando usando os três arquivos `-f` acima. Repetir o mesmo eventId não aplica a alteração novamente. Eventos com erro transiente têm 3 retries espaçados em 2 segundos antes da DLT; comandos inválidos vão diretamente para DLT. Falha ao publicar na DLT não deve descartar o registro original. Para inspecionar: `docker compose exec redpanda rpk topic consume be-api.status-commands.DLT -n 1`.

O publicador mantém eventos na outbox durante indisponibilidade do Kafka e tenta novamente a cada 2 segundos. O envio pode duplicar após falha entre ACK e commit; consumidores de eventos também devem ser idempotentes. O produtor de comandos deve usar customerId como chave. Recibos e outbox ainda não têm limpeza automática.

## Como iniciar as dependências

O Compose inicia automaticamente PostgreSQL, Redpanda (Kafka compatível), Keycloak e WireMock. Na raiz do Nucleus, `make up-base` inicia somente a infraestrutura e `make up-staged` inicia a solução completa.

## Configuração

| Variável | Default local |
|---|---|
| PORT | 4000 |
| JDBC_DATABASE_URL | jdbc:postgresql://be-api-postgres:5432/app |
| DB_USER / DB_PASSWORD | app / app |
| KAFKA_BROKERS | redpanda:9092 |
| KAFKA_TOPIC | be-api.events |
| KAFKA_STATUS_TOPIC | be-api.status-commands |
| KAFKA_GROUP | be-api-status |
| OIDC_ISSUER | http://localhost:8088/auth/realms/nucleus |
| OIDC_JWKS | http://keycloak:8080/auth/realms/nucleus/protocol/openid-connect/certs |
| SCORE_URL | http://score:8080 |
| SCORE_CONNECT_TIMEOUT / SCORE_READ_TIMEOUT | 2s / 3s |
| OUTBOX_DELAY | 2000 ms |

No Nucleus, declare envs próprias em `project.yaml`. O `service.yml` customizado está protegido contra regeneração pelo lock do workload. Migrações Flyway são aplicadas no boot; Hibernate apenas valida o schema. O WireMock responde 750/LOW_RISK para CPFs de 11 dígitos; é uma simulação, não um cálculo real.

## Testes

JDK 21 instalado:

```sh
./gradlew test jacocoTestReport
```

Ou em container, com o cache e artefatos isolados:

```sh
docker run --rm -v "$PWD:/source:ro" -w /tmp/app gradle:8-jdk21 sh -ec 'cp -R /source/. .; sh ./gradlew test jacocoTestReport --no-daemon'
```

A suíte cobre CRUD, validação, autorização, outbox, idempotência e integração HTTP com servidor de teste. Testes de persistência usam H2 em modo PostgreSQL; a execução em containers verifica o PostgreSQL real. O frontend possui testes unitários e E2E de login com Keycloak.

As credenciais e serviços em modo dev deste Compose são destinados apenas a desenvolvimento. O deploy real exige identidade com persistência/HTTPS e permissões de broker configuradas.
