package com.platform.app;

// Alinha as métricas do Micrometer ao contrato da plataforma para o `be-api`.
//
// POR QUE RENOMEAR EM VEZ DE INSTRUMENTAR À MÃO: o Spring Boot já mede toda request em
// `http.server.requests` (que no Prometheus sai como http_server_requests_seconds), com a URI
// NORMALIZADA (/pedido/{id}, não /pedido/42), status e método. Reaproveitar isso é melhor que
// escrever um interceptor: a instrumentação nativa cobre erro, async e rotas de framework.
//
// O que falta é (1) o NOME que o dashboard da plataforma consulta — http_request_duration_seconds,
// igual aos outros workloads — e (2) os BUCKETS, que por padrão o Micrometer não publica (sem eles
// não existe linha _bucket e histogram_quantile() do cartão SLI/SLO não retorna nada).
//
// A tag `uri` é renomeada para `route`, que é o nome usado pelos demais stacks.
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

  /** Nome nativo do Micrometer para a métrica de request do Spring MVC. */
  private static final String SPRING_HTTP = "http.server.requests";

  /** Nome do contrato da plataforma (o que o dashboard consulta). */
  private static final String CONTRACT_HTTP = "http_request_duration_seconds";

  // Os MESMOS buckets dos outros workloads, senão o p95/p99 do dashboard compara distribuições
  // diferentes entre um serviço e outro. Em SEGUNDOS aqui; convertidos p/ nanos no filtro, que é a
  // unidade base do Micrometer.
  private static final double[] BUCKETS_SECONDS = {
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10
  };

  @Bean
  MeterFilter platformContractHttpMetrics() {
    return new MeterFilter() {
      @Override
      public Meter.Id map(Meter.Id id) {
        return SPRING_HTTP.equals(id.getName()) ? id.withName(CONTRACT_HTTP) : id;
      }

      @Override
      public DistributionStatisticConfig configure(
          Meter.Id id, DistributionStatisticConfig config) {
        // Aceita os dois nomes: dependendo da ordem de aplicação dos filtros, o configure pode
        // ver o id antes ou depois do map.
        if (!CONTRACT_HTTP.equals(id.getName()) && !SPRING_HTTP.equals(id.getName())) {
          return config;
        }

        double[] nanos = new double[BUCKETS_SECONDS.length];
        for (int i = 0; i < BUCKETS_SECONDS.length; i++) {
          nanos[i] = BUCKETS_SECONDS[i] * 1_000_000_000d;
        }

        // serviceLevelObjectives é o que faz o registry Prometheus publicar as linhas _bucket.
        return DistributionStatisticConfig.builder()
            .serviceLevelObjectives(nanos)
            .build()
            .merge(config);
      }
    };
  }

  /** `uri` é o nome do Micrometer; o resto da plataforma usa `route`. */
  @Bean
  MeterFilter platformContractRouteTag() {
    return MeterFilter.renameTag(CONTRACT_HTTP, "uri", "route");
  }
}
