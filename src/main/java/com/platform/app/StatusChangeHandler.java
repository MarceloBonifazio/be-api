package com.platform.app;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatusChangeHandler {
  private final CustomerRepository customers;
  private final ProcessedEventRepository processed;

  public StatusChangeHandler(CustomerRepository customers, ProcessedEventRepository processed) {
    this.customers = customers;
    this.processed = processed;
  }

  @Transactional
  public void handle(Command command) {
    if (command == null
        || command.eventId() == null
        || command.customerId() == null
        || command.customerId() < 1
        || command.status() == null
        || !"CUSTOMER_STATUS_CHANGE".equals(command.eventType()))
      throw new IllegalArgumentException("Comando de status inválido");
    if (processed.existsById(command.eventId())) return;
    // Trava o cliente antes de consultar o recibo, serializando duplicatas concorrentes.
    var customer =
        customers
            .findLockedById(command.customerId())
            .orElseThrow(() -> new IllegalArgumentException("Cliente do comando inexistente"));
    if (processed.existsById(command.eventId())) return;
    customer.status = command.status();
    customer.updatedAt = java.time.Instant.now();
    processed.saveAndFlush(new ProcessedEvent(command.eventId()));
  }

  public record Command(UUID eventId, String eventType, Long customerId, CustomerStatus status) {}
}
