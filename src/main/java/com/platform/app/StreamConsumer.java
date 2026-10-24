// Workload `be-cep-api` (Spring Boot) roda um stream consumer (kafka) em background (thread daemon, via
// ApplicationRunner) e faz a bridge SSE em GET /stream → externamente /api/be-cep-api/stream (EventSource no
// browser, sem falar com o broker direto). O consumer faz broadcast() das msgs pros SseEmitter conectados.
// Schema das msgs no repo `contract` (AsyncAPI).
//
// Espelha o consumer hand-written do java/cru, mas: (1) o consumer roda num ApplicationRunner (thread daemon),
// (2) a bridge usa SseEmitter (Spring MVC) em vez de PrintWriter manual. Set thread-safe porque o consumer
// roda noutra thread e os clientes entram/saem na thread do Tomcat.
package com.platform.app;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@RestController
public class StreamConsumer implements ApplicationRunner {
    // Bridge SSE: o consumer (thread daemon) faz broadcast das msgs aqui; o browser consome em
    // /api/be-cep-api/stream via EventSource. Set thread-safe (consumer escreve, Tomcat adiciona/remove).
    private static final Set<SseEmitter> SSE_CLIENTS = ConcurrentHashMap.newKeySet();

    // SSE em GET /stream → externamente /api/be-cep-api/stream. SseEmitter sem timeout (0L = nunca expira);
    // registra no broadcaster e remove no completion/timeout/erro (cliente caiu).
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> SSE_CLIENTS.remove(emitter));
        emitter.onTimeout(() -> SSE_CLIENTS.remove(emitter));
        emitter.onError(e -> SSE_CLIENTS.remove(emitter));
        SSE_CLIENTS.add(emitter);
        return emitter;
    }

    private static void broadcast(String json) {
        // Cada msg vira um evento SSE (`data: <json>\n\n`). Cliente que falhou ao escrever é descartado.
        for (SseEmitter e : SSE_CLIENTS) {
            try { e.send(SseEmitter.event().data(json)); }
            catch (IOException | IllegalStateException ex) { SSE_CLIENTS.remove(e); }
        }
    }

    // Escapa uma String como literal JSON (entre aspas). O contrato das msgs (AsyncAPI) mora no repo `contract`.
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // Stream consumer (kafka) em background. ApplicationRunner sobe numa thread DAEMON; o Tomcat (http)
    // segue na thread principal e mantém a JVM viva.
    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(() -> {
            try { consume(); }
            catch (Exception e) { System.err.println("[be-cep-api] stream: " + e.getMessage()); }
        }, "stream");
        t.setDaemon(true);
        t.start();
    }

    private void consume() throws Exception {
        var env = System.getenv();
        String brokers = env.getOrDefault("KAFKA_BROKERS", "redpanda:9092");
        String topic = env.getOrDefault("KAFKA_TOPIC", "be-cep-api.events");
        String group = env.getOrDefault("KAFKA_GROUP", "be-cep-api");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            System.out.println("[be-cep-api] consumindo topic=" + topic + " group=" + group + " brokers=" + brokers);
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> record : records) {
                    // TODO: processe a mensagem conforme o contrato (AsyncAPI).
                    System.out.println("[be-cep-api] msg partition=" + record.partition() + " offset=" + record.offset());
                    broadcast("{\"partition\":" + record.partition() + ",\"offset\":" + record.offset()
                        + ",\"value\":" + jsonString(record.value()) + "}");
                }
            }
        }
    }
}
