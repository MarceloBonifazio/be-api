package com.platform.app;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "customer_activities")
public class CustomerActivity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  @Column(nullable = false) public Long customerId;
  @Column(nullable = false, length = 32) public String type;
  @Column(nullable = false, length = 254) public String description;
  @Column(nullable = false) public Instant createdAt;

  protected CustomerActivity() {}

  CustomerActivity(Long customerId, String type, String description) {
    this.customerId = customerId;
    this.type = type;
    this.description = description;
    this.createdAt = Instant.now();
  }
}
