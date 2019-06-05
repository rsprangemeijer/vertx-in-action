package tenksteps.ingester;

import io.vertx.amqp.AmqpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.amqp.AmqpClient;
import io.vertx.reactivex.amqp.AmqpMessage;
import io.vertx.reactivex.core.RxHelper;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.kafka.admin.KafkaAdminClient;
import io.vertx.reactivex.kafka.client.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class IngesterTest {

  static Map<String, String> kafkaConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("bootstrap.servers", "localhost:9092");
    config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    config.put("value.deserializer", "io.vertx.kafka.client.serialization.JsonObjectDeserializer");
    config.put("auto.offset.reset", "earliest");
    config.put("enable.auto.commit", "false");
    config.put("group.id", "ingester-test-" + System.currentTimeMillis());
    return config;
  }

  private KafkaConsumer<String, JsonObject> kafkaConsumer;

  static AmqpClientOptions amqClientOptions() {
    return new AmqpClientOptions()
      .setHost("localhost")
      .setPort(5672)
      .setUsername("artemis")
      .setPassword("simetraehcapa");
  }

  private AmqpClient amqpClient;

  @BeforeEach
  void setup(Vertx vertx, VertxTestContext testContext) {
    kafkaConsumer = KafkaConsumer.create(vertx, kafkaConfig());
    amqpClient = AmqpClient.create(vertx, amqClientOptions());
    KafkaAdminClient adminClient = KafkaAdminClient.create(vertx, kafkaConfig());
    vertx
      .rxDeployVerticle(new Main())
      .flatMapCompletable(id -> adminClient.rxDeleteTopics(singletonList("incoming.steps")))
      .onErrorComplete()
      .subscribe(testContext::completeNow, testContext::failNow);
  }

  @Test
  @DisplayName("Ingest a well-formed AMQP message")
  void amqIngest(VertxTestContext testContext) {
    JsonObject body = new JsonObject()
      .put("deviceId", "123")
      .put("deviceSync", 1L)
      .put("stepsCount", 500);

    amqpClient.rxConnect()
      .flatMap(connection -> connection.rxCreateSender("step-events"))
      .subscribe(
        sender -> {
          AmqpMessage msg = AmqpMessage.create().withJsonObjectAsBody(body).build();
          sender.send(msg);
        },
        testContext::failNow);

    kafkaConsumer.subscribe("incoming.steps")
      .toFlowable()
      .subscribe(
        record -> testContext.verify(() -> {
          assertThat(record.key()).isEqualTo("123");
          JsonObject json = record.value();
          assertThat(json.getString("deviceId")).isEqualTo("123");
          assertThat(json.getLong("deviceSync")).isEqualTo(1L);
          assertThat(json.getInteger("stepsCount")).isEqualTo(500);
          testContext.completeNow();
        }),
        testContext::failNow);
  }

  @Test
  @DisplayName("Ingest a badly-formed AMQP message and observe no Kafka record")
  void amqIngestWrong(Vertx vertx, VertxTestContext testContext) {
    JsonObject body = new JsonObject();

    amqpClient.rxConnect()
      .flatMap(connection -> connection.rxCreateSender("step-events"))
      .subscribe(
        sender -> {
          AmqpMessage msg = AmqpMessage.create().withJsonObjectAsBody(body).build();
          sender.send(msg);
        },
        testContext::failNow);

    kafkaConsumer.subscribe("incoming.steps")
      .toFlowable()
      .timeout(3, TimeUnit.SECONDS, RxHelper.scheduler(vertx))
      .subscribe(
        record -> testContext.failNow(new IllegalStateException("We must not get a record")),
        err -> {
          if (err instanceof TimeoutException) {
            testContext.completeNow();
          } else {
            testContext.failNow(err);
          }
        });
  }
}