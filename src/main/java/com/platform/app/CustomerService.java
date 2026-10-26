package com.platform.app;

import java.time.Instant;
import java.util.UUID;
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

  public CustomerService(CustomerRepository customers, OutboxRepository outbox, ObjectMapper json) {
    this.customers = customers;
    this.outbox = outbox;
    this.json = json;
  }

  public CustomerView create(CustomerInput input) {
    if (customers.existsByCpf(input.cpf()))
      throw new ApiException(HttpStatus.CONFLICT, "CPF já cadastrado.");
    var c = customers.saveAndFlush(new Customer(input));
    var id = UUID.randomUUID();
    var now = Instant.now();
    var event = new CreatedEvent(id, "CUSTOMER_CREATED", c.id, now);
    outbox.save(new OutboxEvent(id, c.id, json.writeValueAsString(event), now));
    return CustomerView.of(c);
  }

  public CustomerView update(long id, CustomerInput input) {
    var c = require(id);
    if (customers.existsByCpfAndIdNot(input.cpf(), id))
      throw new ApiException(HttpStatus.CONFLICT, "CPF já cadastrado.");
    c.apply(input);
    customers.flush();
    return CustomerView.of(c);
  }

  public void delete(long id) {
    customers.delete(require(id));
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
    return customers
        .findByNameContainingIgnoreCase(
            name.trim(), PageRequest.of(page, size, Sort.by("id").descending()))
        .map(CustomerView::of);
  }

  private Customer require(long id) {
    return customers
        .findById(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente não encontrado."));
  }

  record CreatedEvent(UUID eventId, String eventType, Long customerId, Instant createdAt) {}
}
