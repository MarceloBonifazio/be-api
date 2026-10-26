package com.platform.app;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
  @Id public UUID eventId;

  @Column(nullable = false)
  public Long customerId;

  @Column(nullable = false, columnDefinition = "text")
  public String payload;

  @Column(nullable = false)
  public Instant createdAt;

  public Instant publishedAt;

  protected OutboxEvent() {}

  OutboxEvent(UUID eventId, Long customerId, String payload, Instant createdAt) {
    this.eventId = eventId;
    this.customerId = customerId;
    this.payload = payload;
    this.createdAt = createdAt;
  }
}
