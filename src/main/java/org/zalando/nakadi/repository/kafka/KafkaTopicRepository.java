package org.zalando.nakadi.repository.kafka;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import kafka.admin.AdminUtils;
import kafka.common.TopicExistsException;
import kafka.utils.ZkUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotLeaderForPartitionException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.domain.BatchItem;
import org.zalando.nakadi.domain.EventPublishingStatus;
import org.zalando.nakadi.domain.EventPublishingStep;
import org.zalando.nakadi.domain.SubscriptionBase;
import org.zalando.nakadi.domain.TopicPosition;
import org.zalando.nakadi.exceptions.EventPublishingException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.TopicCreationException;
import org.zalando.nakadi.exceptions.TopicDeletionException;
import org.zalando.nakadi.repository.EventConsumer;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.zookeeper.ZooKeeperHolder;
import org.zalando.nakadi.repository.zookeeper.ZookeeperSettings;
import org.zalando.nakadi.util.UUIDGenerator;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toList;
import static org.zalando.nakadi.domain.CursorError.NULL_OFFSET;
import static org.zalando.nakadi.domain.CursorError.NULL_PARTITION;
import static org.zalando.nakadi.domain.CursorError.PARTITION_NOT_FOUND;
import static org.zalando.nakadi.domain.CursorError.UNAVAILABLE;

@Component
@Profile("!test")
public class KafkaTopicRepository implements TopicRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicRepository.class);

    private final ZooKeeperHolder zkFactory;
    private final KafkaFactory kafkaFactory;
    private final NakadiSettings nakadiSettings;
    private final KafkaSettings kafkaSettings;
    private final ZookeeperSettings zookeeperSettings;
    private final ConcurrentMap<String, HystrixKafkaCircuitBreaker> circuitBreakers;
    private final UUIDGenerator uuidGenerator;

    @Autowired
    public KafkaTopicRepository(final ZooKeeperHolder zkFactory,
                                final KafkaFactory kafkaFactory,
                                final NakadiSettings nakadiSettings,
                                final KafkaSettings kafkaSettings,
                                final ZookeeperSettings zookeeperSettings,
                                final UUIDGenerator uuidGenerator) {
        this.zkFactory = zkFactory;
        this.kafkaFactory = kafkaFactory;
        this.nakadiSettings = nakadiSettings;
        this.kafkaSettings = kafkaSettings;
        this.zookeeperSettings = zookeeperSettings;
        this.uuidGenerator = uuidGenerator;
        this.circuitBreakers = new ConcurrentHashMap<>();
    }

    public List<String> listTopics() throws ServiceUnavailableException {
        try {
            return zkFactory.get()
                    .getChildren()
                    .forPath("/brokers/topics");
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Failed to list topics", e);
        }
    }

    @Override
    public String createTopic(final int partitionCount, final Long retentionTimeMs)
            throws TopicCreationException {
        if (retentionTimeMs == null) {
            throw new IllegalArgumentException("Retention time can not be null");
        }
        final String topicName = uuidGenerator.randomUUID().toString();
        createTopic(topicName,
                partitionCount,
                nakadiSettings.getDefaultTopicReplicaFactor(),
                retentionTimeMs,
                nakadiSettings.getDefaultTopicRotationMs());
        // calculateKafkaPartitionCount(eventType.getDefaultStatistic())
        return topicName;
    }

    private void createTopic(final String topic, final int partitionsNum, final int replicaFactor,
                             final long retentionMs, final long rotationMs)
            throws TopicCreationException {
        try {
            doWithZkUtils(zkUtils -> {
                final Properties topicConfig = new Properties();
                topicConfig.setProperty("retention.ms", Long.toString(retentionMs));
                topicConfig.setProperty("segment.ms", Long.toString(rotationMs));
                AdminUtils.createTopic(zkUtils, topic, partitionsNum, replicaFactor, topicConfig);
            });
        } catch (final TopicExistsException e) {
            throw new TopicCreationException("Topic with name " + topic +
                    " already exists (or wasn't completely removed yet)", e);
        } catch (final Exception e) {
            throw new TopicCreationException("Unable to create topic " + topic, e);
        }
    }

    @Override
    public void deleteTopic(final String topic) throws TopicDeletionException {
        try {
            // this will only trigger topic deletion, but the actual deletion is asynchronous
            doWithZkUtils(zkUtils -> AdminUtils.deleteTopic(zkUtils, topic));
        } catch (final Exception e) {
            throw new TopicDeletionException("Unable to delete topic " + topic, e);
        }
    }

    @Override
    public boolean topicExists(final String topic) throws ServiceUnavailableException {
        return listTopics()
                .stream()
                .anyMatch(t -> t.equals(topic));
    }

    private static CompletableFuture<Exception> publishItem(
            final Producer<String, String> producer,
            final String topicId,
            final BatchItem item,
            final HystrixKafkaCircuitBreaker circuitBreaker) throws EventPublishingException {
        try {
            final CompletableFuture<Exception> result = new CompletableFuture<>();
            final ProducerRecord<String, String> kafkaRecord = new ProducerRecord<>(
                    topicId,
                    KafkaCursor.toKafkaPartition(item.getPartition()),
                    item.getPartition(),
                    item.getEvent().toString());

            circuitBreaker.markStart();
            producer.send(kafkaRecord, ((metadata, exception) -> {
                if (null != exception) {
                    LOG.warn("Failed to publish to kafka topic {}", topicId, exception);
                    item.updateStatusAndDetail(EventPublishingStatus.FAILED, "internal error");
                    if (hasKafkaConnectionException(exception)) {
                        circuitBreaker.markFailure();
                    } else {
                        circuitBreaker.markSuccessfully();
                    }
                    result.complete(exception);
                } else {
                    item.updateStatusAndDetail(EventPublishingStatus.SUBMITTED, "");
                    circuitBreaker.markSuccessfully();
                    result.complete(null);
                }
            }));
            return result;
        } catch (final InterruptException e) {
            circuitBreaker.markSuccessfully();
            item.updateStatusAndDetail(EventPublishingStatus.FAILED, "internal error");
            throw new EventPublishingException("Error publishing message to kafka", e);
        } catch (final RuntimeException e) {
            circuitBreaker.markSuccessfully();
            item.updateStatusAndDetail(EventPublishingStatus.FAILED, "internal error");
            throw new EventPublishingException("Error publishing message to kafka", e);
        }
    }

    private static boolean isExceptionShouldLeadToReset(@Nullable final Exception exception) {
        if (null == exception) {
            return false;
        }
        return Stream.of(NotLeaderForPartitionException.class, UnknownTopicOrPartitionException.class)
                .anyMatch(clazz -> clazz.isAssignableFrom(exception.getClass()));
    }

    private static boolean hasKafkaConnectionException(final Exception exception) {
        return exception instanceof org.apache.kafka.common.errors.TimeoutException ||
                exception instanceof NetworkException ||
                exception instanceof UnknownServerException;
    }

    @Override
    public void syncPostBatch(final String topicId, final List<BatchItem> batch) throws EventPublishingException {
        final Producer<String, String> producer = kafkaFactory.takeProducer();
        try {
            final Map<String, String> partitionToBroker = producer.partitionsFor(topicId).stream().collect(
                    Collectors.toMap(p -> String.valueOf(p.partition()), p -> String.valueOf(p.leader().id())));
            batch.forEach(item -> {
                Preconditions.checkNotNull(
                        item.getPartition(), "BatchItem partition can't be null at the moment of publishing!");
                item.setBrokerId(partitionToBroker.get(item.getPartition()));
            });

            int shortCircuited = 0;
            final Map<BatchItem, CompletableFuture<Exception>> sendFutures = new HashMap<>();
            for (final BatchItem item : batch) {
                item.setStep(EventPublishingStep.PUBLISHING);
                final HystrixKafkaCircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(
                        item.getBrokerId(), brokerId -> new HystrixKafkaCircuitBreaker(brokerId));
                if (circuitBreaker.allowRequest()) {
                    sendFutures.put(item, publishItem(producer, topicId, item, circuitBreaker));
                } else {
                    shortCircuited++;
                    item.updateStatusAndDetail(EventPublishingStatus.FAILED, "short circuited");
                }
            }
            if (shortCircuited > 0) {
                LOG.warn("Short circuiting request to Kafka {} time(s) due to timeout for topic {}",
                        shortCircuited, topicId);
            }
            final CompletableFuture<Void> multiFuture = CompletableFuture.allOf(
                    sendFutures.values().toArray(new CompletableFuture<?>[sendFutures.size()]));
            multiFuture.get(createSendTimeout(), TimeUnit.MILLISECONDS);

            // Now lets check for errors
            final Optional<Exception> needReset = sendFutures.entrySet().stream()
                    .filter(entry -> isExceptionShouldLeadToReset(entry.getValue().getNow(null)))
                    .map(entry -> entry.getValue().getNow(null))
                    .findAny();
            if (needReset.isPresent()) {
                LOG.info("Terminating producer while publishing to topic {} because of unrecoverable exception",
                        topicId, needReset.get());
                kafkaFactory.terminateProducer(producer);
            }
        } catch (final TimeoutException ex) {
            failUnpublished(batch, "timed out");
            throw new EventPublishingException("Error publishing message to kafka", ex);
        } catch (final ExecutionException ex) {
            failUnpublished(batch, "internal error");
            throw new EventPublishingException("Error publishing message to kafka", ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            failUnpublished(batch, "interrupted");
            throw new EventPublishingException("Error publishing message to kafka", ex);
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
        final boolean atLeastOneFailed = batch.stream()
                .anyMatch(item -> item.getResponse().getPublishingStatus() == EventPublishingStatus.FAILED);
        if (atLeastOneFailed) {
            failUnpublished(batch, "internal error");
            throw new EventPublishingException("Error publishing message to kafka");
        }
    }

    private long createSendTimeout() {
        return nakadiSettings.getKafkaSendTimeoutMs() + kafkaSettings.getRequestTimeoutMs();
    }

    private void failUnpublished(final List<BatchItem> batch, final String reason) {
        batch.stream()
                .filter(item -> item.getResponse().getPublishingStatus() != EventPublishingStatus.SUBMITTED)
                .filter(item -> item.getResponse().getDetail().isEmpty())
                .forEach(item -> item.updateStatusAndDetail(EventPublishingStatus.FAILED, reason));
    }

    @Override
    public List<TopicPosition> loadNewestPosition(final Collection<String> topicIds)
            throws ServiceUnavailableException {
        return loadTopicPosition(topicIds, true).stream().map(KafkaCursor::toNakadiPosition).collect(toList());
    }

    @Override
    public List<TopicPosition> loadOldestPosition(final Collection<String> topicIds, final boolean positionOnExisting)
            throws ServiceUnavailableException {
        return loadTopicPosition(topicIds, false).stream()
                .map(k -> positionOnExisting ? k.addOffset(1) : k)
                .map(KafkaCursor::toNakadiPosition).collect(toList());
    }


    private List<KafkaCursor> loadTopicPosition(final Collection<String> topicIds, final boolean end)
            throws ServiceUnavailableException {
        try (final Consumer<String, String> consumer = kafkaFactory.getConsumer()) {
            final List<TopicPartition> kafkaTPs =
                    topicIds.stream().flatMap(
                            topic -> consumer.partitionsFor(topic).stream().map(
                                    p -> new TopicPartition(p.topic(), p.partition())))
                            .collect(Collectors.toList());
            consumer.assign(kafkaTPs);
            if (end) {
                consumer.seekToEnd(kafkaTPs.toArray(new TopicPartition[kafkaTPs.size()]));
            } else {
                consumer.seekToBeginning(kafkaTPs.toArray(new TopicPartition[kafkaTPs.size()]));
            }
            return kafkaTPs.stream()
                    .map(tp -> new KafkaCursor(tp.topic(), tp.partition(), consumer.position(tp)))
                    .collect(Collectors.toList());
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Error occurred when fetching partitions offsets", e);
        }
    }

    @Override
    public Map<String, Long> materializePositions(final String topicId, final SubscriptionBase.InitialPosition position)
            throws ServiceUnavailableException {
        try (final Consumer<String, String> consumer = kafkaFactory.getConsumer()) {

            final org.apache.kafka.common.TopicPartition[] kafkaTPs = consumer
                    .partitionsFor(topicId)
                    .stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topicId, p.partition()))
                    .toArray(org.apache.kafka.common.TopicPartition[]::new);
            consumer.assign(Arrays.asList(kafkaTPs));
            if (position == SubscriptionBase.InitialPosition.BEGIN) {
                consumer.seekToBeginning(kafkaTPs);
            } else if (position == SubscriptionBase.InitialPosition.END) {
                consumer.seekToEnd(kafkaTPs);
            } else {
                throw new IllegalArgumentException("Bad offset specification " + position + " for topic " + topicId);
            }
            return Stream.of(kafkaTPs).collect(Collectors.toMap(
                    tp -> String.valueOf(tp.partition()),
                    consumer::position));
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Error occurred when fetching partitions offsets", e);
        }

    }

    @Override
    public List<String> listPartitionNames(final String topicId) {
        final Producer<String, String> producer = kafkaFactory.takeProducer();
        try {
            return unmodifiableList(producer.partitionsFor(topicId)
                    .stream()
                    .map(partitionInfo -> KafkaCursor.toNakadiPartition(partitionInfo.partition()))
                    .collect(toList()));
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
    }

    public Consumer<String, String> createKafkaConsumer() {
        return kafkaFactory.getConsumer();
    }

    @Override
    public EventConsumer createEventConsumer(final List<TopicPosition> cursors)
            throws ServiceUnavailableException, InvalidCursorException {
        return kafkaFactory.createNakadiConsumer(
                this.validateCursors(cursors),
                nakadiSettings.getKafkaPollTimeoutMs());
    }

    public int compareOffsets(final TopicPosition first, final TopicPosition second) {
        try {
            // TODO: This code should be removed in future.
            return KafkaCursor.fromNakadiPosition(first).compareTo(KafkaCursor.fromNakadiPosition(second));
        } catch (final InvalidCursorException e) {
            throw new IllegalArgumentException("Incorrect offset format, should be long", e);
        }
    }

    private List<KafkaCursor> validateCursors(final List<TopicPosition> cursors) throws ServiceUnavailableException,
            InvalidCursorException {
        final List<String> topics = cursors.stream().map(TopicPosition::getTopic).distinct().collect(toList());
        final Map<Integer, KafkaCursor> oldest = loadTopicPosition(topics, false).stream().collect(
                Collectors.toMap(KafkaCursor::getPartition, v -> v));
        final Map<Integer, KafkaCursor> newest = loadTopicPosition(topics, true).stream().collect(
                Collectors.toMap(KafkaCursor::getPartition, v -> v));

        final List<KafkaCursor> result = new ArrayList<>(cursors.size());
        for (final TopicPosition position : cursors) {
            validateCursorForNulls(position);
            final KafkaCursor proposedCursor = KafkaCursor.fromNakadiPosition(position);
            if (!newest.containsKey(proposedCursor.getPartition())) {
                throw new InvalidCursorException(PARTITION_NOT_FOUND, position);
            }
            if (proposedCursor.compareTo(oldest.get(proposedCursor.getPartition())) < 0) {
                throw new InvalidCursorException(UNAVAILABLE, position);
            } else if (proposedCursor.compareTo(newest.get(proposedCursor.getPartition())) > 0) {
                throw new InvalidCursorException(UNAVAILABLE, position);
            } else {
                result.add(proposedCursor);
            }
        }
        return result;
    }

    @Override
    public void validateCommitCursor(final TopicPosition position) throws InvalidCursorException {
        final List<String> partitions = this.listPartitionNames(position.getTopic());
        validateCursorForNulls(position);
        if (!partitions.contains(position.getPartition())) {
            throw new InvalidCursorException(PARTITION_NOT_FOUND, position);
        }
        KafkaCursor.fromNakadiPosition(position);
    }

    private void validateCursorForNulls(final TopicPosition cursor) throws InvalidCursorException {
        if (cursor.getPartition() == null) {
            throw new InvalidCursorException(NULL_PARTITION, cursor);
        }
        if (cursor.getOffset() == null) {
            throw new InvalidCursorException(NULL_OFFSET, cursor);
        }
    }

    @FunctionalInterface
    private interface ZkUtilsAction {
        void execute(ZkUtils zkUtils) throws Exception;
    }

    private void doWithZkUtils(final ZkUtilsAction action) throws Exception {
        ZkUtils zkUtils = null;
        try {
            final String connectionString = zkFactory.get().getZookeeperClient().getCurrentConnectionString();
            zkUtils = ZkUtils.apply(connectionString, zookeeperSettings.getZkSessionTimeoutMs(),
                    zookeeperSettings.getZkConnectionTimeoutMs(), false);
            action.execute(zkUtils);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }
}
