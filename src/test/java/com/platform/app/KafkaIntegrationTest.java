package com.platform.app;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "app.kafka.consumer-enabled=true",
      "spring.datasource.url=jdbc:h2:mem:kafkatest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("test")
@DirtiesContext
@EmbeddedKafka(
    partitions = 3,
    topics = {"be-api.status-commands", "be-api.status-commands.DLT"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaIntegrationTest {
  @Autowired CustomerService customers;
  @Autowired ProcessedEventRepository processed;
  @Autowired KafkaTemplate<String, String> template;
  @Autowired EmbeddedKafkaBroker broker;

  @Test
  void realConsumerDeduplicatesAndQuarantinesMalformedMessages() throws Exception {
    var customer =
        customers.create(
            new CustomerInput(
                "Kafka Test", "11144477735", "kafka@example.com", CustomerStatus.ACTIVE));
    String eventId = UUID.randomUUID().toString();
    String command =
        "{\"eventId\":\""
            + eventId
            + "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":"
            + customer.id()
            + ",\"status\":\"INACTIVE\"}";
    template
        .send("be-api.status-commands", customer.id().toString(), command)
        .get(10, TimeUnit.SECONDS);
    await(() -> processed.existsById(UUID.fromString(eventId)));
    assertEquals(CustomerStatus.INACTIVE, customers.get(customer.id()).status());
    template
        .send("be-api.status-commands", customer.id().toString(), command)
        .get(10, TimeUnit.SECONDS);
    var props = new java.util.HashMap<String, Object>();
    props.put("bootstrap.servers", broker.getBrokersAsString());
    props.put("group.id", "dlt-verification");
    props.put("auto.offset.reset", "earliest");
    props.put("enable.auto.commit", false);
    try (var consumer =
        new DefaultKafkaConsumerFactory<String, String>(
                props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer())
            .createConsumer()) {
      broker.consumeFromAnEmbeddedTopic(consumer, "be-api.status-commands.DLT");
      template
          .send("be-api.status-commands", customer.id().toString(), "invalid-json")
          .get(10, TimeUnit.SECONDS);
      var bad =
          KafkaTestUtils.getSingleRecord(
              consumer, "be-api.status-commands.DLT", Duration.ofSeconds(20));
      assertEquals("invalid-json", bad.value());
    }
    assertEquals(1, processed.count());
  }

  private void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
    assertTrue(condition.getAsBoolean(), "Kafka consumer did not complete in time");
  }
}
