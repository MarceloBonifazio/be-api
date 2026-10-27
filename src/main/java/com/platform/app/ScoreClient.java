package com.platform.app;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class ScoreClient implements ScoreGateway {
  private final RestClient client;

  public ScoreClient(
      @Value("${app.score.url}") String url,
      @Value("${app.score.connect-timeout}") Duration connect,
      @Value("${app.score.read-timeout}") Duration read) {
    var factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
    factory.setReadTimeout(read);
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public Score get(String cpf) {
    try {
      var result = client.get().uri("/scores/{cpf}", cpf).retrieve().body(Score.class);
      if (result == null
          || !cpf.equals(result.cpf())
          || result.score() == null
          || result.score() < 0
          || result.score() > 1000
          || result.classification() == null
          || result.classification().length() > 32
          || result.classification().isBlank())
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Resposta inválida do serviço de Score.");
      return result;
    } catch (ResourceAccessException e) {
      boolean timeout = false;
      for (Throwable t = e; t != null; t = t.getCause())
        if (t instanceof java.net.http.HttpTimeoutException
            || t instanceof java.net.SocketTimeoutException) timeout = true;
      throw new ApiException(
          timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE,
          timeout
              ? "Tempo limite do serviço de Score excedido."
              : "Serviço de Score indisponível.");
    } catch (RestClientResponseException e) {
      throw new ApiException(
          e.getStatusCode().value() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY,
          e.getStatusCode().value() == 404
              ? "Score não encontrado para este CPF."
              : "Falha no serviço de Score.");
    } catch (RestClientException e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "Resposta inválida do serviço de Score.");
    }
  }

  public record Score(String cpf, Integer score, String classification) {}
}
