package com.platform.app;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, length = 150)
  public String name;

  @Column(nullable = false, length = 11, unique = true)
  public String cpf;

  @Column(nullable = false, length = 254)
  public String email;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public CustomerStatus status;

  @Version public long version;

  @Column(nullable = false)
  public Instant createdAt;

  @Column(nullable = false)
  public Instant updatedAt;

  protected Customer() {}

  Customer(CustomerInput input) {
    apply(input);
    createdAt = Instant.now();
  }

  void apply(CustomerInput input) {
    name = input.name().trim();
    cpf = input.cpf();
    email = input.email().trim();
    status = input.status();
    updatedAt = Instant.now();
  }
}
