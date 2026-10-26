package com.platform.app;

import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, error) ->
                new org.apache.kafka.common.TopicPartition(
                    record.topic() + ".DLT", record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class, tools.jackson.core.JacksonException.class);
    return handler;
  }
}
