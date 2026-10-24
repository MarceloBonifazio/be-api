package com.platform;

// Métricas do `be-cep-api` — contrato da plataforma:
//   http_requests_total            (counter)    total de requests
//   http_request_duration_seconds  (histogram)  duração, nos buckets padrão do Prometheus
// mais as métricas de runtime da JVM (heap, threads).
//
// SEM DEPENDÊNCIA EXTERNA (só JDK): estes templates entregam o contrato operacional sem arrastar
// Micrometer/simpleclient para o pom.
//
// Fica em `com.platform` de propósito: os templates graphql/grpc/websocket têm a App no DEFAULT
// package, e uma classe do default package PODE importar de um pacote nomeado (o contrário não
// compila). Assim um único arquivo serve todos os stacks java da plataforma:
//   cru      → mesmo pacote (com.platform), sem import
//   quarkus  → mesmo pacote (com.platform), sem import
//   spring   → com.platform.app, faz `import com.platform.Metrics;`
//   graphql/grpc/websocket (default package) → `import com.platform.Metrics;`
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class Metrics {
    private Metrics() { /* utilitário estático */ }

    // Os MESMOS buckets dos outros workloads da plataforma, senão o p95/p99 do dashboard compara
    // distribuições diferentes entre um serviço e outro.
    private static final double[] BUCKETS = { 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10 };

    // Teto de séries (método × rota × status). Rota dinâmica não normalizada (/pedido/1, /pedido/2…)
    // viraria uma série por ID e derrubaria o Prometheus por cardinalidade; passando do teto, o
    // excedente é agregado em route="other".
    private static final int MAX_SERIES = 200;

    private static final class Series {
        final LongAdder count = new LongAdder();
        // Soma em MICROSSEGUNDOS (long) p/ acumular sem lock; o render divide de volta. Um double
        // compartilhado exigiria sincronização e perderia atomicidade.
        final AtomicLong micros = new AtomicLong();
        final LongAdder[] buckets = new LongAdder[BUCKETS.length];

        Series() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
    }

    private static final Map<String, Series> STORE = new ConcurrentHashMap<>();

    private static String key(String method, String route, int status) {
        return method + '\u0000' + route + '\u0000' + status;
    }

    /** Contabiliza uma request. Buckets CUMULATIVOS, como o formato do Prometheus espera. */
    public static void observe(String method, String route, int status, double seconds) {
        String k = key(method, route, status);
        if (!STORE.containsKey(k) && STORE.size() >= MAX_SERIES) {
            k = key(method, "other", status);
        }

        Series s = STORE.computeIfAbsent(k, unused -> new Series());
        s.count.increment();
        s.micros.addAndGet(Math.round(seconds * 1_000_000d));
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                s.buckets[i].increment();
            }
        }
    }

    /** Corpo do /metrics: RED + runtime da JVM. */
    public static String render() {
        final String svc = "service=\"be-cep-api\"";
        StringBuilder sb = new StringBuilder(1024);

        List<Map.Entry<String, Series>> entries = new ArrayList<>(STORE.entrySet());

        sb.append("# HELP http_requests_total Total de requests HTTP.\n");
        sb.append("# TYPE http_requests_total counter\n");
        for (Map.Entry<String, Series> e : entries) {
            sb.append("http_requests_total{").append(labels(svc, e.getKey())).append("} ")
              .append(e.getValue().count.sum()).append('\n');
        }

        sb.append("# HELP http_request_duration_seconds Duração das requests HTTP (s).\n");
        sb.append("# TYPE http_request_duration_seconds histogram\n");
        for (Map.Entry<String, Series> e : entries) {
            String l = labels(svc, e.getKey());
            Series s = e.getValue();
            long count = s.count.sum();
            for (int i = 0; i < BUCKETS.length; i++) {
                sb.append("http_request_duration_seconds_bucket{").append(l)
                  .append(",le=\"").append(num(BUCKETS[i])).append("\"} ").append(s.buckets[i].sum()).append('\n');
            }
            // +Inf fecha o histograma e tem que ser igual ao _count.
            sb.append("http_request_duration_seconds_bucket{").append(l).append(",le=\"+Inf\"} ").append(count).append('\n');
            sb.append("http_request_duration_seconds_sum{").append(l).append("} ")
              .append(num(s.micros.get() / 1_000_000d)).append('\n');
            sb.append("http_request_duration_seconds_count{").append(l).append("} ").append(count).append('\n');
        }

        Runtime rt = Runtime.getRuntime();
        sb.append("# HELP jvm_memory_used_bytes JVM heap usado (bytes).\n");
        sb.append("# TYPE jvm_memory_used_bytes gauge\n");
        sb.append("jvm_memory_used_bytes{").append(svc).append("} ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
        sb.append("# HELP jvm_threads Threads ativas.\n");
        sb.append("# TYPE jvm_threads gauge\n");
        sb.append("jvm_threads{").append(svc).append("} ").append(ManagementFactory.getThreadMXBean().getThreadCount()).append('\n');

        return sb.toString();
    }

    private static String labels(String svc, String key) {
        String[] parts = key.split("\u0000", -1);
        return svc + ",method=\"" + esc(parts[0]) + "\",route=\"" + esc(parts[1]) + "\",status=\"" + esc(parts[2]) + "\"";
    }

    // Locale.ROOT: em pt-BR o separador decimal seria vírgula e o Prometheus rejeitaria o número.
    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        // Tira os zeros à direita (0.500000 → 0.5) e também o ponto órfão, p/ o bucket sair como
        // le="1" e não le="1.0": o label `le` é STRING, então um serviço publicando "1.0" e outro "1"
        // caem em grupos diferentes num `sum by (le)` que cruze workloads.
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // Escape de valor de label conforme o formato de exposição do Prometheus.
    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
