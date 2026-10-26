package com.platform.app;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {
  @Id public UUID eventId;

  @Column(nullable = false)
  public Instant processedAt;

  protected ProcessedEvent() {}

  ProcessedEvent(UUID eventId) {
    this.eventId = eventId;
    processedAt = Instant.now();
  }
}
