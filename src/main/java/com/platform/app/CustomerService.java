package com.platform.app;

import java.time.Instant;
import java.util.UUID;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class CustomerService {
  private final CustomerRepository customers;
  private final OutboxRepository outbox;
  private final ObjectMapper json;
  private final CustomerActivityRepository activities;

  public CustomerService(CustomerRepository customers, OutboxRepository outbox, ObjectMapper json,
      CustomerActivityRepository activities) {
    this.customers = customers;
    this.outbox = outbox;
    this.json = json;
    this.activities = activities;
  }

  public CustomerView create(CustomerInput input) {
    if (customers.existsByCpf(input.cpf()))
      throw new ApiException(HttpStatus.CONFLICT, "CPF já cadastrado.");
    var c = customers.saveAndFlush(new Customer(input));
    var id = UUID.randomUUID();
    var now = Instant.now();
    var event = new CreatedEvent(id, "CUSTOMER_CREATED", c.id, now);
    outbox.save(new OutboxEvent(id, c.id, json.writeValueAsString(event), now));
    activity(c.id, "CUSTOMER_CREATED", "Cliente cadastrado; evento incluído na outbox Kafka.");
    return CustomerView.of(c);
  }

  public CustomerView update(long id, CustomerInput input) {
    var c = require(id);
    if (customers.existsByCpfAndIdNot(input.cpf(), id))
      throw new ApiException(HttpStatus.CONFLICT, "CPF já cadastrado.");
    c.apply(input);
    customers.flush();
    activity(c.id, "CUSTOMER_UPDATED", "Dados do cliente atualizados.");
    return CustomerView.of(c);
  }

  public void delete(long id) {
    customers.delete(require(id));
    activity(id, "CUSTOMER_DELETED", "Cliente excluído.");
  }

  @Transactional(readOnly = true)
  public CustomerView get(long id) {
    return CustomerView.of(require(id));
  }

  @Transactional(readOnly = true)
  public Page<CustomerView> list(CustomerStatus status, int page, int size) {
    var paging = PageRequest.of(page, size, Sort.by("id").descending());
    return (status == null ? customers.findAll(paging) : customers.findByStatus(status, paging))
        .map(CustomerView::of);
  }

  @Transactional(readOnly = true)
  public Page<CustomerView> search(String name, int page, int size) {
    return search(name, null, page, size);
  }

  @Transactional(readOnly = true)
  public Page<CustomerView> search(String query, CustomerStatus status, int page, int size) {
    String term = query.trim().toLowerCase(Locale.ROOT);
    String digits = term.matches("[0-9.\\-\\s]+") ? term.replaceAll("[^0-9]", "") : "";
    return customers.search("%" + escapeLike(term) + "%",
        digits.isEmpty() ? "" : "%" + digits + "%", status,
        PageRequest.of(page, size, Sort.by("id").descending())).map(CustomerView::of);
  }

  private String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  @Transactional(readOnly = true)
  public Summary summary() {
    var monthStart = LocalDate.now(ZoneId.of("America/Sao_Paulo")).withDayOfMonth(1)
        .atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant();
    return new Summary(customers.count(), customers.countByStatus(CustomerStatus.ACTIVE),
        customers.countByStatus(CustomerStatus.INACTIVE), customers.averageScore(),
        customers.countByScoreIsNotNull(), customers.countByCreatedAtGreaterThanEqual(monthStart));
  }

  public CustomerView recordScore(long id, String expectedCpf, ScoreClient.Score score) {
    var c = require(id);
    if (!c.cpf.equals(expectedCpf))
      throw new ApiException(HttpStatus.CONFLICT, "CPF alterado durante a consulta. Consulte novamente.");
    c.score = score.score();
    c.classification = score.classification();
    c.scoreQueriedAt = Instant.now();
    activity(id, "SCORE_QUERIED", "Score consultado: " + score.score() + " / 1000.");
    customers.flush();
    return CustomerView.of(c);
  }

  @Transactional(readOnly = true)
  public Page<CustomerActivity> events(Long customerId, int page, int size) {
    if (customerId != null) require(customerId);
    var paging = PageRequest.of(page, size, Sort.by("id").descending());
    return customerId == null ? activities.findAll(paging)
        : activities.findByCustomerId(customerId, paging);
  }

  private void activity(long id, String type, String description) {
    activities.save(new CustomerActivity(id, type, description));
  }

  public record Summary(long total, long active, long inactive, Double averageScore,
      long scoredCustomers, long newThisMonth) {}

  private Customer require(long id) {
    return customers
        .findById(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente não encontrado."));
  }

  record CreatedEvent(UUID eventId, String eventType, Long customerId, Instant createdAt) {}
}
