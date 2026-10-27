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

  public Integer score;

  @Column(length = 32)
  public String classification;

  public Instant scoreQueriedAt;

  protected Customer() {}

  Customer(CustomerInput input) {
    apply(input);
    createdAt = Instant.now();
  }

  void apply(CustomerInput input) {
    if (cpf != null && !cpf.equals(input.cpf())) {
      score = null;
      classification = null;
      scoreQueriedAt = null;
    }
    name = input.name().trim();
    cpf = input.cpf();
    email = input.email().trim();
    status = input.status();
    updatedAt = Instant.now();
  }
}
