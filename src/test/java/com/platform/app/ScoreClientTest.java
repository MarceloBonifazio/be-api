package com.platform.app;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.*;

class ScoreClientTest {
  HttpServer server;
  ScoreClient client;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    client =
        new ScoreClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  void respond(int status, String body, long delay) {
    server.createContext(
        "/scores/52998224725",
        exchange -> {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @Test
  void returnsValidScore() {
    respond(200, "{\"cpf\":\"52998224725\",\"score\":750,\"classification\":\"LOW_RISK\"}", 0);
    assertEquals(750, client.get("52998224725").score());
  }

  @Test
  void handlesMissingScore() {
    respond(404, "{}", 0);
    assertEquals(
        404, assertThrows(ApiException.class, () -> client.get("52998224725")).status.value());
  }

  @Test
  void handlesUpstreamFailure() {
    respond(503, "{}", 0);
    assertEquals(
        502, assertThrows(ApiException.class, () -> client.get("52998224725")).status.value());
  }

  @Test
  void rejectsMalformedBody() {
    respond(200, "not-json", 0);
    assertEquals(
        502, assertThrows(ApiException.class, () -> client.get("52998224725")).status.value());
  }

  @Test
  void rejectsWrongCpf() {
    respond(200, "{\"cpf\":\"123\",\"score\":750,\"classification\":\"LOW_RISK\"}", 0);
    assertEquals(
        502, assertThrows(ApiException.class, () -> client.get("52998224725")).status.value());
  }

  @Test
  void timesOut() {
    client =
        new ScoreClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            Duration.ofSeconds(1),
            Duration.ofMillis(150));
    respond(200, "{}", 700);
    assertEquals(
        504, assertThrows(ApiException.class, () -> client.get("52998224725")).status.value());
  }

  @Test
  void handlesConnectionFailure() {
    server.start();
    server.stop(0);
    assertEquals(
        503, assertThrows(ApiException.class, () -> client.get("52998224725")).status.value());
  }
}
