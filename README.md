# score — gestão de clientes

Java 21 · Spring Boot 4.1 · Gradle Wrapper · PostgreSQL/JPA/Flyway · Kafka · OAuth2/JWT.

## Requisitos

- Docker Desktop/Engine com Compose v2;
- Git e acesso às imagens Docker;
- 6 GB de memória disponíveis para PostgreSQL, Kafka/Redpanda, Keycloak, WireMock e as aplicações.

## Executar

Use a stack standalone definida em `docker-compose.yml` para executar a solução completa. Os projetos `be-api` e `fe-painel` devem estar lado a lado. No diretório `be-api`, execute:

```sh
docker compose up --build -d
```

Acesse **http://localhost:8088/painel/**. Login local: `admin` / `admin-dev` (USER + ADMIN) ou `user` / `user-dev` (somente USER). A primeira execução baixa imagens e dependências e pode levar alguns minutos. A porta 8088 deve estar disponível; se outro serviço estiver usando essa porta, libere-a antes de iniciar esta stack.

Para conferir os serviços e acompanhar os logs, no mesmo diretório:

```sh
docker compose ps
docker compose logs -f gateway be-api fe-painel
```

Para parar a stack: `docker compose down`. Os volumes preservam os dados. As configurações de identidade são importadas apenas na primeira inicialização do realm; mudanças no arquivo não substituem automaticamente um realm existente.

Para rebuildar o backend e recriar também o WireMock (serviço `score`):

```sh
docker compose up -d --build --force-recreate be-api score
```

O WireMock usa uma imagem pronta e os arquivos montados de `dev/score`. Se alterar apenas os mappings, recarregue-os reiniciando o serviço:

```sh
docker compose restart score
```

## Arquitetura

Um serviço de clientes com camadas HTTP, aplicação, JPA e adaptadores Kafka/Score. Keycloak é o servidor de identidade; a API é Resource Server JWT. O frontend é uma SPA separada e o Score é simulado por WireMock.

POST grava cliente e evento na mesma transação (outbox). Um publicador periódico envia os eventos pendentes. O consumidor altera status e registra `eventId` na mesma transação, tolerando duplicatas. O sistema usa entrega pelo menos uma vez, sem transação distribuída.

## Autenticação e autorização

No painel, o login redireciona ao Keycloak usando Authorization Code + PKCE S256. O token fica em memória. A API exige assinatura válida, emissor, expiração e audiência `be-api` nas rotas `/customers`. USER consulta; ADMIN também escreve. As rotas técnicas `/health`, `/live`, `/version`, `/docs`, `/openapi.json` e `/actuator/**` são públicas.

Para Postman/Insomnia configure OAuth 2.0 Authorization Code com PKCE:

- Authorization URL: `http://localhost:8088/auth/realms/nucleus/protocol/openid-connect/auth`
- Token URL: `http://localhost:8088/auth/realms/nucleus/protocol/openid-connect/token`
- Client ID: `fe-painel`; sem client secret; PKCE S256; escopo `openid`.
- Cadastre o callback exato da sua ferramenta no cliente do Keycloak pelo console `http://localhost:8088/auth/admin/` (operador local `operator` / `operator-dev`). Os callbacks iniciais permitem apenas o painel.

Use o Access Token em `Authorization: Bearer <token>`. Não use ID Token. Fluxo de senha direta está desabilitado.

### Rotas do Keycloak

Base pública: `http://localhost:8088/auth`. O gateway encaminha `/auth` ao Keycloak; dentro da rede Docker, a base é `http://keycloak:8080/auth`.

| Método | Caminho relativo à base | Uso |
|---|---|---|
| GET | `/admin/` | Console administrativo; login local `operator` / `operator-dev` |
| GET | `/realms/nucleus/account/` | Conta do usuário do realm `nucleus` |
| GET | `/realms/nucleus/.well-known/openid-configuration` | Descoberta dos endpoints OpenID Connect |
| GET | `/realms/nucleus/protocol/openid-connect/auth` | Login via Authorization Code + PKCE; requer os parâmetros OAuth2 |
| POST | `/realms/nucleus/protocol/openid-connect/token` | Troca do código por tokens e renovação via refresh token |
| GET | `/realms/nucleus/protocol/openid-connect/certs` | Chaves públicas (JWKS) para validar tokens |

O painel usa o cliente público `fe-painel`. Os usuários de demonstração são `admin` / `admin-dev` e `user` / `user-dev`; o usuário `operator` administra o Keycloak.

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

### Cenários de Score no WireMock

Base interna: `http://score:8080`. O Compose não publica uma porta do WireMock no host e o gateway não possui rota para ele; portanto, `http://localhost:8088/scores/...` não dá acesso ao WireMock.

| Método | Caminho | Uso |
|---|---|---|
| GET | `/scores/{cpf}` | Consulta simulada de score, conforme os cenários abaixo |
| GET | `/__admin/mappings` | Inspeciona os mappings carregados |
| GET | `/__admin/requests` | Inspeciona as requisições recebidas |

Para consultar diretamente pela rede Docker, usando a imagem curl em um container temporário:

```sh
docker run --rm --network nucleus-customers_default curlimages/curl:latest -sS http://score:8080/scores/12345678143
docker run --rm --network nucleus-customers_default curlimages/curl:latest -sS http://score:8080/__admin/mappings
```

Pela API autenticada, use `GET http://localhost:8088/api/be-api/customers/{id}/score`, com o ID de um cliente cadastrado. Os CPFs dos cenários específicos abaixo têm dígitos verificadores válidos e podem ser cadastrados na API. Os exemplos `abc123` e `12345` são intencionalmente inválidos e servem para testar diretamente as respostas 400 do WireMock.

Na stack local, os resultados abaixo são simulações fornecidas pelo WireMock (não são calculados pela API). O status é o status HTTP configurado na resposta do WireMock. A resposta lenta aguarda 15 segundos; como o timeout de leitura padrão da API é 3 segundos, a chamada pela API pode terminar com 504 antes de receber essa resposta.

| CPF | Status no WireMock | Resultado |
|---|---:|---|
| `12345678143` | 200 | `LOW_RISK` — 850 |
| `12345678224` | 200 | `MEDIUM_RISK` — 550 |
| `12345678305` | 200 | `HIGH_RISK` — 250 |
| `12345678496` | 200 | `HIGH_RISK` — 0 |
| `12345678577` | 200 | `LOW_RISK` — 1000 |
| `12345678658` | 503 | Serviço indisponível |
| `12345678739` | 200 | Delay de 15 segundos (`LOW_RISK` — 700) |
| `12345678810` | 500 | Erro interno |
| `12345678909` | 404 | Score não encontrado |
| `abc123` | 400 | CPF inválido |
| `12345` | 400 | CPF incompleto |
| Qualquer outro CPF de 11 dígitos | 200 | `LOW_RISK` — 750 |

A regra de CPF numérico com tamanho inválido aceita apenas comprimentos diferentes de 11 dígitos. Assim, CPFs de 11 dígitos sem cenário específico usam o fallback de score 750. Os cenários explícitos da tabela têm prioridade 1.

## Kafka

Tópicos locais com 3 partições:

- `be-api.events`: CUSTOMER_CREATED, com eventId, eventType, customerId e createdAt; chave = customerId.
- `be-api.status-commands`: CUSTOMER_STATUS_CHANGE.
- `be-api.status-commands.DLT`: mensagens inválidas ou com tentativas esgotadas.

No diretório `be-api`, publique um comando de alteração de status para um cliente existente:

```sh
printf '%s\n' '{"eventId":"cbca5352-22ad-48f2-aaf2-704735bc7737","eventType":"CUSTOMER_STATUS_CHANGE","customerId":1,"status":"INACTIVE"}' | docker compose exec -T redpanda rpk topic produce be-api.status-commands -k 1
```

Substitua `customerId` e a chave `-k` pelo ID do cliente desejado e use um novo `eventId` para cada comando. Repetir o mesmo eventId não aplica a alteração novamente. Eventos com erro transiente têm 3 novas tentativas espaçadas em 2 segundos antes da DLT; comandos inválidos vão diretamente para DLT. Falha ao publicar na DLT não deve descartar o registro original. Para inspecionar: `docker compose exec redpanda rpk topic consume be-api.status-commands.DLT -n 1`.

O publicador mantém eventos na outbox durante indisponibilidade do Kafka e tenta novamente a cada 2 segundos. O envio pode duplicar após falha entre ACK e commit; consumidores de eventos também devem ser idempotentes. O produtor de comandos deve usar customerId como chave. Recibos e outbox ainda não têm limpeza automática.

## Como iniciar as dependências

O Compose inicia automaticamente PostgreSQL, Redpanda (Kafka compatível), Keycloak e WireMock.

## Configuração

| Variável | Valor na stack local |
|---|---|
| PORT | 4000 |
| JDBC_DATABASE_URL | jdbc:postgresql://postgres:5432/app |
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

Na stack standalone, ajuste as variáveis em `services.be-api.environment` no `docker-compose.yml`. O arquivo `.env` fornece valores para interpolação do Compose, mas não é repassado automaticamente ao container. As variáveis não declaradas no Compose usam os valores padrão de `src/main/resources/application.properties`.

Migrações Flyway são aplicadas na inicialização; Hibernate apenas valida o schema.

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
