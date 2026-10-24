package com.platform.app;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Contrato da plataforma: /health (liveness) · /ready (readiness) · /version · /metrics (Prometheus)
//   + operacionais: /live · /info · /build · /config · /status. Traefik faz strip de /api → paths sem prefixo.
@RestController
public class HealthController {
    private final io.micrometer.prometheusmetrics.PrometheusMeterRegistry registry;

    public HealthController(io.micrometer.prometheusmetrics.PrometheusMeterRegistry registry) {
        this.registry = registry;
    }
    private static final String SERVICE = "service";
    private static final String STATUS = "status";
    private static final String NAME = "be-cep-api";
    private static final String UP = "UP";
    private static final String UNKNOWN = "unknown";
    private static final String ENV_VERSION = "APP_VERSION";
    private static final String ENV_APP = "APP_ENV";
    private static final String DEVELOPMENT = "development";

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(STATUS, UP);
    }

    @GetMapping("/live")
    public Map<String, String> live() {
        return Map.of(STATUS, UP);
    }

    // /ready (readiness): checa conectividade com os recursos DECLARADOS (env-gated). Conexão efêmera por
    // chamada; cada check em try/catch → "ok" ou a mensagem do erro. 200 se todos ok, 503 se algum falha.
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, String> checks = new LinkedHashMap<>();
        var env = System.getenv();
        try {
            java.util.Properties props = new java.util.Properties();
            props.put("bootstrap.servers", env.getOrDefault("KAFKA_BROKERS", "redpanda:9092"));
            props.put("request.timeout.ms", "3000");
            props.put("default.api.timeout.ms", "3000");
            try (org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(props)) {
                admin.describeCluster().nodes().get(3, java.util.concurrent.TimeUnit.SECONDS);
            }
            checks.put("kafka", UP);
        } catch (Exception e) {
            checks.put("kafka", "DOWN");
        }
        boolean ready = checks.values().stream().allMatch(UP::equals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(STATUS, ready ? "READY" : "NOT_READY");
        body.put(SERVICE, NAME);
        body.put("checks", checks);
        return ResponseEntity.status(ready ? 200 : 503).body(body);
    }

    @GetMapping("/version")
    public Map<String, String> version() {
        var env = System.getenv();
        return Map.of(
            SERVICE, NAME,
            "version", env.getOrDefault(ENV_VERSION, "0.0.1"),
            "commit", env.getOrDefault("GIT_COMMIT", UNKNOWN),
            "branch", env.getOrDefault("GIT_BRANCH", UNKNOWN),
            "buildTime", env.getOrDefault("BUILD_TIME", UNKNOWN),
            "runtime", "jvm " + System.getProperty("java.version"),
            "environment", env.getOrDefault(ENV_APP, DEVELOPMENT));
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        var env = System.getenv();
        String region = env.getOrDefault("REGION",
            env.getOrDefault("CLOUD_REGION", env.getOrDefault("AWS_REGION", UNKNOWN)));
        return Map.of(
            SERVICE, NAME,
            "environment", env.getOrDefault(ENV_APP, DEVELOPMENT),
            "region", region,
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
            "runtime", "jvm " + System.getProperty("java.version"));
    }

    @GetMapping("/build")
    public Map<String, String> build() {
        var env = System.getenv();
        return Map.of(
            SERVICE, NAME,
            "commit", env.getOrDefault("GIT_COMMIT", UNKNOWN),
            "branch", env.getOrDefault("GIT_BRANCH", UNKNOWN),
            "buildTime", env.getOrDefault("BUILD_TIME", UNKNOWN),
            "image", env.getOrDefault("IMAGE", UNKNOWN),
            "tag", env.getOrDefault("IMAGE_TAG", env.getOrDefault(ENV_VERSION, UNKNOWN)),
            "pipeline", env.getOrDefault("CI_PIPELINE", UNKNOWN));
    }

    // /config: dump do ambiente, mascarando valores de chaves sensíveis (SECRET/TOKEN/KEY/URL/...).
    @GetMapping("/config")
    public Map<String, Object> config() {
        Pattern sensitive = Pattern.compile(
            "(?i)(SECRET|PASSWORD|PASSWD|TOKEN|KEY|VAULT|CRED|PRIVATE|_URL|_DSN|_ADDR|_BROKERS|CONTACT_POINTS|CONNECTION)");
        Map<String, String> cfg = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            cfg.put(e.getKey(), sensitive.matcher(e.getKey()).find() ? "***" : e.getValue());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(SERVICE, NAME);
        body.put("config", cfg);
        return body;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4")
    public String metrics() {
        return registry.scrape();
    }

    // /status (consolidado): mesma readiness gated do /ready + version. 200 se READY, 503 se NOT_READY.
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        var env = System.getenv();
        Map<String, String> checks = new LinkedHashMap<>();
        try {
            java.util.Properties props = new java.util.Properties();
            props.put("bootstrap.servers", env.getOrDefault("KAFKA_BROKERS", "redpanda:9092"));
            props.put("request.timeout.ms", "3000");
            props.put("default.api.timeout.ms", "3000");
            try (org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(props)) {
                admin.describeCluster().nodes().get(3, java.util.concurrent.TimeUnit.SECONDS);
            }
            checks.put("kafka", UP);
        } catch (Exception e) {
            checks.put("kafka", "DOWN");
        }
        boolean ready = checks.values().stream().allMatch(UP::equals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(STATUS, ready ? "READY" : "NOT_READY");
        body.put(SERVICE, NAME);
        body.put("version", env.getOrDefault(ENV_VERSION, "0.0.1"));
        body.put("checks", checks);
        return ResponseEntity.status(ready ? 200 : 503).body(body);
    }

    // /openapi.json (spec ESTÁTICO do contrato) + /docs (Swagger via Scalar). O openapi.json é gerado na
    // raiz do projeto (templates/_contract) e copiado p/ o WORKDIR (/app) no estágio runner do Dockerfile → lido em runtime.
    @GetMapping(value = "/openapi.json", produces = "application/json")
    public String openapi() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("openapi.json"));
        } catch (Exception e) {
            return "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"be-cep-api\",\"version\":\"0.0.1\"},\"paths\":{}}";
        }
    }

    // /docs some em produção (mesma env do /version): só expõe a UI fora de prod.
    @GetMapping(value = "/docs", produces = "text/html")
    public ResponseEntity<String> docs() {
        var env = System.getenv();
        if ("production".equals(env.getOrDefault(ENV_APP, DEVELOPMENT))) {
            return ResponseEntity.status(404).build();
        }
        // Lê o docs.html do WORKDIR (gerado no root pelo _contract e copiado no estágio runner do Dockerfile), espelhando o
        // openapi.json. Se faltar, cai num fallback mínimo (Scalar via CDN) apontando p/ o /openapi.json ao lado.
        String html;
        try {
            html = java.nio.file.Files.readString(java.nio.file.Path.of("docs.html"));
        } catch (Exception e) {
            html = "<!doctype html><html><head><title>be-cep-api · API</title><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body><script id=\"api-reference\" data-url=\"openapi.json\"></script><script src=\"https://cdn.jsdelivr.net/npm/@scalar/api-reference\"></script></body></html>";
        }
        return ResponseEntity.ok(html);
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(SERVICE, NAME, "kind", "api");
    }
}
