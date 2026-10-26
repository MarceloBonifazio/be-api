package com.platform.app;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.outbox.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPublisher {
  private final OutboxRepository repository;
  private final KafkaTemplate<String, String> kafka;
  private final String topic;

  public OutboxPublisher(
      OutboxRepository repository,
      KafkaTemplate<String, String> kafka,
      @Value("${app.kafka.events-topic}") String topic) {
    this.repository = repository;
    this.kafka = kafka;
    this.topic = topic;
  }

  @Scheduled(fixedDelayString = "${app.outbox.delay}")
  @Transactional
  public void publish() {
    for (var event : repository.lockPending()) {
      try {
        kafka.send(topic, event.customerId.toString(), event.payload).get(12, TimeUnit.SECONDS);
        event.publishedAt = Instant.now();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass())
            .warn("Kafka indisponível; evento {} permanece na outbox", event.eventId);
        return;
      }
    }
  }
}
