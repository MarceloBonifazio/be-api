package com.platform.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired CustomerRepository customers;
  @Autowired OutboxRepository outbox;
  @Autowired ProcessedEventRepository processed;
  @Autowired CustomerService service;
  @Autowired StatusChangeHandler handler;
  @Autowired ObjectMapper json;
  @Autowired CustomerActivityRepository activities;
  @MockitoBean ScoreGateway scores;

  private RequestPostProcessor role(String role) {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private final String input =
      "{\"name\":\"João da"
          + " Silva\",\"cpf\":\"52998224725\",\"email\":\"joao@example.com\",\"status\":\"ACTIVE\"}";

  @BeforeEach
  void clean() {
    activities.deleteAll();
    processed.deleteAll();
    outbox.deleteAll();
    customers.deleteAll();
  }

  @Test
  void protectsAllBusinessAndOperationalRoutes() throws Exception {
    for (String path :
        List.of(
            "/customers",
            "/customers/1",
            "/customers/search?name=joao",
            "/customers/1/score",
            "/customers/summary",
            "/customers/events",
            "/customers/1/events")) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get("/health")).andExpect(status().isOk());
    mvc.perform(get("/live")).andExpect(status().isOk());
    mvc.perform(get("/version")).andExpect(status().isOk());
    mvc.perform(get("/docs")).andExpect(status().isOk());
    mvc.perform(get("/openapi.json")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/config").with(role("USER"))).andExpect(status().isForbidden());
    mvc.perform(get("/customers").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void userCanReadButCannotWrite() throws Exception {
    mvc.perform(get("/customers").with(role("USER"))).andExpect(status().isOk());
    mvc.perform(
            post("/customers")
                .with(role("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/customers/1")
                .with(role("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/customers/1").with(role("USER"))).andExpect(status().isForbidden());
    mvc.perform(get("/customers").with(jwt())).andExpect(status().isForbidden());
  }

  @Test
  void createsUpdatesSearchesFiltersAndDeletes() throws Exception {
    var response =
        mvc.perform(
                post("/customers")
                    .with(role("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(input))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn();
    long id = json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    assertEquals(1, outbox.count());
    var event = json.readTree(outbox.findAll().getFirst().payload);
    assertEquals("CUSTOMER_CREATED", event.get("eventType").asString());
    assertEquals(id, event.get("customerId").asLong());
    mvc.perform(get("/customers/" + id).with(role("USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cpf").value("52998224725"));
    mvc.perform(get("/customers/search?name=JOÃO").with(role("USER")))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            put("/customers/" + id)
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.replace("ACTIVE", "INACTIVE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"));
    mvc.perform(get("/customers?status=ACTIVE").with(role("USER")))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/customers?status=INACTIVE").with(role("USER")))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(delete("/customers/" + id).with(role("ADMIN"))).andExpect(status().isNoContent());
    mvc.perform(get("/customers/" + id).with(role("USER"))).andExpect(status().isNotFound());
  }

  @Test
  void validatesDataAndDuplicateCpf() throws Exception {
    mvc.perform(
            post("/customers")
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.replace("52998224725", "11111111111")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/customers")
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.replace("joao@example.com", "invalid")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/customers")
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/customers")
                .with(role("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isConflict());
    mvc.perform(get("/customers?size=101").with(role("USER"))).andExpect(status().isBadRequest());
    mvc.perform(get("/customers?status=OTHER").with(role("USER")))
        .andExpect(status().isBadRequest());
    assertEquals(1, customers.count());
    assertEquals(1, outbox.count());
  }

  @Test
  void appliesCommandAtomicallyAndIgnoresOldDuplicate() {
    var c =
        service.create(
            new CustomerInput("João", "52998224725", "joao@example.com", CustomerStatus.ACTIVE));
    var first =
        new StatusChangeHandler.Command(
            UUID.randomUUID(), "CUSTOMER_STATUS_CHANGE", c.id(), CustomerStatus.INACTIVE);
    handler.handle(first);
    handler.handle(
        new StatusChangeHandler.Command(
            UUID.randomUUID(), "CUSTOMER_STATUS_CHANGE", c.id(), CustomerStatus.ACTIVE));
    handler.handle(first);
    assertEquals(CustomerStatus.ACTIVE, customers.findById(c.id()).orElseThrow().status);
    assertEquals(2, processed.count());
  }

  @Test
  void rejectsInvalidCommandWithoutReceipt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            handler.handle(
                new StatusChangeHandler.Command(
                    UUID.randomUUID(), "OTHER", 1L, CustomerStatus.ACTIVE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            handler.handle(
                new StatusChangeHandler.Command(
                    UUID.randomUUID(), "CUSTOMER_STATUS_CHANGE", 999L, CustomerStatus.ACTIVE)));
    assertEquals(0, processed.count());
  }

  @Test
  void searchesNameCpfAndEmailTogetherWithStatus() throws Exception {
    service.create(new CustomerInput("João Silva", "52998224725", "joao@example.com", CustomerStatus.ACTIVE));
    service.create(new CustomerInput("Maria", "12345678143", "maria@example.com", CustomerStatus.INACTIVE));
    for (String query : List.of("JOÃO", "529.982.247-25", "JOAO@EXAMPLE")) {
      mvc.perform(get("/customers").param("query", query).param("status", "ACTIVE").with(role("USER")))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].cpf").value("52998224725"));
    }
    mvc.perform(get("/customers").param("query", "maria").param("status", "ACTIVE").with(role("USER")))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/customers").param("query", "%").with(role("USER")))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/customers/search").param("name", "maria@example.com").param("status", "INACTIVE").with(role("USER")))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void persistsScoreAndReturnsRealSummaryAndActivityHistory() throws Exception {
    var c = service.create(new CustomerInput("João", "52998224725", "joao@example.com", CustomerStatus.ACTIVE));
    service.create(new CustomerInput("Maria", "12345678143", "maria@example.com", CustomerStatus.INACTIVE));
    mvc.perform(get("/customers/summary").with(role("USER")))
        .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.active").value(1))
        .andExpect(jsonPath("$.inactive").value(1)).andExpect(jsonPath("$.scoredCustomers").value(0))
        .andExpect(jsonPath("$.averageScore").isEmpty());
    when(scores.get("52998224725")).thenReturn(new ScoreClient.Score("52998224725", 750, "LOW_RISK"));
    mvc.perform(get("/customers/{id}/score", c.id()).with(role("USER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.cpf").value("52998224725"))
        .andExpect(jsonPath("$.score").value(750)).andExpect(jsonPath("$.queriedAt").isNotEmpty());
    verify(scores).get("52998224725");
    mvc.perform(get("/customers/{id}", c.id()).with(role("USER")))
        .andExpect(jsonPath("$.score").value(750)).andExpect(jsonPath("$.scoreQueriedAt").isNotEmpty());
    mvc.perform(get("/customers/summary").with(role("USER")))
        .andExpect(jsonPath("$.averageScore").value(750.0)).andExpect(jsonPath("$.scoredCustomers").value(1))
        .andExpect(jsonPath("$.newThisMonth").value(2));
    mvc.perform(get("/customers/{id}/events", c.id()).with(role("ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].type").value("SCORE_QUERIED"));
    mvc.perform(get("/customers/events").with(role("ADMIN")))
        .andExpect(jsonPath("$.totalElements").value(3));
    mvc.perform(get("/customers/summary")).andExpect(status().isUnauthorized());
    mvc.perform(get("/customers/events")).andExpect(status().isUnauthorized());
    mvc.perform(get("/customers/{id}/events", Long.MAX_VALUE).with(role("ADMIN")))
        .andExpect(status().isNotFound());
  }

  @Test
  void changingCpfClearsScoreAndRejectsStaleResults() {
    var c = service.create(new CustomerInput("João", "52998224725", "joao@example.com", CustomerStatus.ACTIVE));
    service.recordScore(c.id(), c.cpf(), new ScoreClient.Score(c.cpf(), 0, "HIGH_RISK"));
    assertEquals(0.0, service.summary().averageScore());
    var updated = service.update(c.id(), new CustomerInput("João", "12345678143", "joao@example.com", CustomerStatus.ACTIVE));
    assertNull(updated.score());
    assertNull(updated.classification());
    assertNull(updated.scoreQueriedAt());
    assertEquals(409, assertThrows(ApiException.class, () -> service.recordScore(c.id(), c.cpf(),
        new ScoreClient.Score(c.cpf(), 750, "LOW_RISK"))).status.value());
    assertEquals(0, service.summary().scoredCustomers());
  }

  @Test
  void failedScoreDoesNotReplacePreviousConsultation() throws Exception {
    var c = service.create(new CustomerInput("João", "52998224725", "joao@example.com", CustomerStatus.ACTIVE));
    service.recordScore(c.id(), c.cpf(), new ScoreClient.Score(c.cpf(), 750, "LOW_RISK"));
    when(scores.get(c.cpf())).thenThrow(new ApiException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "Tempo limite do serviço de Score excedido."));
    mvc.perform(get("/customers/{id}/score", c.id()).with(role("USER"))).andExpect(status().isGatewayTimeout());
    assertEquals(750, service.get(c.id()).score());
    assertEquals(2, service.events(c.id(), 0, 20).getTotalElements());
  }
  @Test
  void onlyAdminCanReadGlobalAndCustomerEvents() throws Exception {
    var c = service.create(new CustomerInput("João", "52998224725", "joao@example.com", CustomerStatus.ACTIVE));
    for (String path : List.of("/customers/events", "/customers/" + c.id() + "/events")) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).with(role("USER"))).andExpect(status().isForbidden());
      mvc.perform(get(path).with(role("ADMIN"))).andExpect(status().isOk());
    }
  }

}
