package com.platform.app;

import java.time.Instant;

public record CustomerView(
    Long id,
    String name,
    String cpf,
    String email,
    CustomerStatus status,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Integer score,
    String classification,
    Instant scoreQueriedAt) {
  static CustomerView of(Customer c) {
    return new CustomerView(
        c.id, c.name, c.cpf, c.email, c.status, c.version, c.createdAt, c.updatedAt,
        c.score, c.classification, c.scoreQueriedAt);
  }
}
