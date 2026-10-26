package com.platform.app;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class StatusChangeConsumer {
  private final StatusChangeHandler handler;
  private final ObjectMapper json;

  public StatusChangeConsumer(StatusChangeHandler handler, ObjectMapper json) {
    this.handler = handler;
    this.json = json;
  }

  @KafkaListener(
      topics = "${app.kafka.status-topic}",
      autoStartup = "${app.kafka.consumer-enabled:true}")
  public void consume(String payload) {
    handler.handle(json.readValue(payload, StatusChangeHandler.Command.class));
  }
}
