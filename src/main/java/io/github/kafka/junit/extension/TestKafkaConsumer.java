package io.github.kafka.junit.extension;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
class TestKafkaConsumer {
    @Getter
    private final BlockingQueue<ConsumerRecord<Object, Object>> recordsQueue;
    private final KafkaConsumer<Object, Object> consumer;
    private final int partitions;
    private final String topic;
    private final AtomicBoolean consumerOn = new AtomicBoolean(false);

    private TestKafkaConsumer(BlockingQueue<ConsumerRecord<Object, Object>> recordsQueue,
                              KafkaConsumer<Object, Object> consumer,
                              int partitions,
                              String topic) {
        this.recordsQueue = recordsQueue;
        this.consumer = consumer;
        this.partitions = partitions;
        this.topic = topic;
    }

    @SneakyThrows
    public void start() {
        log.debug("Start test kafka consumer");
        consumer.subscribe(List.of(topic));
        consumerOn.set(true);
        new Thread(() -> {
            boolean isAssignment = false;
            while (consumerOn.get()) {
                while (!isAssignment) {
                    var records = consumer.poll(Duration.ofMillis(100));
                    final Set<TopicPartition> assignment = consumer.assignment();
                    log.trace("Assignments: " + assignment.size());
                    if (assignment.size() == partitions) {
                        isAssignment = true;
                    } else {
                        try {
                            TimeUnit.MILLISECONDS.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (!records.isEmpty()) {
                        for (var record : records) {
                            try {
                                recordsQueue.put(record);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        consumer.commitSync();
                    }
                }
                var records = consumer.poll(Duration.ofMillis(100));
                if (!records.isEmpty()) {
                    for (var record : records) {
                        try {
                            recordsQueue.put(record);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    consumer.commitSync();
                }
            }
        }).start();
    }

    public void stop() {
        log.debug("Stop test kafka consumer");
        consumerOn.set(false);
        log.debug("Test kafka consumer stopped");
    }

    public static TestKafkaConsumer consumer(String topic,
                                             String bootstrapServers,
                                             int partitions,
                                             Class<?> keyDeserializerClass,
                                             Class<?> valueDeserializerClass
    ) {
        var props = commonProps(bootstrapServers, keyDeserializerClass, valueDeserializerClass);
        return createConsumer(topic, partitions, props);
    }

    public static TestKafkaConsumer consumerWithAdditionalProperties(String topic,
                                                                     String bootstrapServers,
                                                                     int partitions,
                                                                     Class<?> keyDeserializerClass,
                                                                     Class<?> valueDeserializerClass,
                                                                     Map<String, Object> props) {
        log.trace("Start to create test consumer for topic: {} and props: {}", topic, props);
        var commonProps = commonProps(bootstrapServers, keyDeserializerClass, valueDeserializerClass);
        commonProps.putAll(props);
        var testConsumer = createConsumer(topic, partitions, commonProps);
        log.trace("Created test consumer: {}", testConsumer);
        return testConsumer;
    }

    private static TestKafkaConsumer createConsumer(String topic,
                                                    int partitions,
                                                    Properties props) {
        log.trace("Start to create test consumer for topic: {}", topic);
        var queue = new LinkedBlockingQueue<ConsumerRecord<Object, Object>>();
        var consumer = new KafkaConsumer<>(props);
        var testConsumer = new TestKafkaConsumer(queue, consumer, partitions, topic);
        log.trace("Created test consumer: {}", testConsumer);
        return testConsumer;
    }

    private static Properties commonProps(String bootstrapServers,
                                                   Class<?> keyDeserializerClass,
                                                   Class<?> valueDeserializerClass) {
        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializerClass);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializerClass);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        return consumerProps;
    }
}
