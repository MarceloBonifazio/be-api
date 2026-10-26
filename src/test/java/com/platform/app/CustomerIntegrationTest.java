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

  private RequestPostProcessor role(String role) {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private final String input =
      "{\"name\":\"João da"
          + " Silva\",\"cpf\":\"52998224725\",\"email\":\"joao@example.com\",\"status\":\"ACTIVE\"}";

  @BeforeEach
  void clean() {
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
            "/customers/1/score")) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get("/health")).andExpect(status().isOk());
    mvc.perform(get("/live")).andExpect(status().isOk());
    mvc.perform(get("/version")).andExpect(status().isOk());
    mvc.perform(get("/docs")).andExpect(status().isOk());
    mvc.perform(get("/openapi.json")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/config")).andExpect(status().isForbidden());
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
}
