package com.platform.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxPublisherTest {
  @Test
  void onlyMarksPublishedAfterBrokerAcknowledgment() {
    var repo = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var event = new OutboxEvent(UUID.randomUUID(), 1L, "{}", Instant.now());
    when(repo.lockPending()).thenReturn(List.of(event));
    when(kafka.send("events", "1", "{}"))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("offline")));
    var publisher = new OutboxPublisher(repo, kafka, "events");
    publisher.publish();
    assertNull(event.publishedAt);
    when(kafka.send("events", "1", "{}")).thenReturn(CompletableFuture.completedFuture(null));
    publisher.publish();
    assertNotNull(event.publishedAt);
  }
}
