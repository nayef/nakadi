package org.zalando.nakadi.controller;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.zalando.nakadi.domain.CursorError;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.TopicPosition;
import org.zalando.nakadi.exceptions.IllegalScopeException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.NoConnectionSlotsException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.UnparseableCursorException;
import org.zalando.nakadi.repository.EventConsumer;
import org.zalando.nakadi.repository.EventTypeRepository;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.security.Client;
import org.zalando.nakadi.service.BlacklistService;
import org.zalando.nakadi.service.ClosedConnectionsCrutch;
import org.zalando.nakadi.service.ConnectionSlot;
import org.zalando.nakadi.service.ConsumerLimitingService;
import org.zalando.nakadi.service.EventStream;
import org.zalando.nakadi.service.EventStreamConfig;
import org.zalando.nakadi.service.EventStreamFactory;
import org.zalando.nakadi.util.FeatureToggleService;
import org.zalando.nakadi.view.Cursor;
import org.zalando.problem.Problem;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.PRECONDITION_FAILED;
import static org.zalando.nakadi.metrics.MetricUtils.metricNameFor;
import static org.zalando.nakadi.util.FeatureToggleService.Feature.LIMIT_CONSUMERS_NUMBER;

@RestController
public class EventStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(EventStreamController.class);
    public static final String CONSUMERS_COUNT_METRIC_NAME = "consumers";

    private final EventTypeRepository eventTypeRepository;
    private final TopicRepository topicRepository;
    private final ObjectMapper jsonMapper;
    private final EventStreamFactory eventStreamFactory;
    private final MetricRegistry metricRegistry;
    private final ClosedConnectionsCrutch closedConnectionsCrutch;
    private final BlacklistService blacklistService;
    private final ConsumerLimitingService consumerLimitingService;
    private final FeatureToggleService featureToggleService;

    @Autowired
    public EventStreamController(final EventTypeRepository eventTypeRepository, final TopicRepository topicRepository,
                                 final ObjectMapper jsonMapper, final EventStreamFactory eventStreamFactory,
                                 final MetricRegistry metricRegistry,
                                 final ClosedConnectionsCrutch closedConnectionsCrutch,
                                 final BlacklistService blacklistService,
                                 final ConsumerLimitingService consumerLimitingService,
                                 final FeatureToggleService featureToggleService) {
        this.eventTypeRepository = eventTypeRepository;
        this.topicRepository = topicRepository;
        this.jsonMapper = jsonMapper;
        this.eventStreamFactory = eventStreamFactory;
        this.metricRegistry = metricRegistry;
        this.closedConnectionsCrutch = closedConnectionsCrutch;
        this.blacklistService = blacklistService;
        this.consumerLimitingService = consumerLimitingService;
        this.featureToggleService = featureToggleService;
    }

    @VisibleForTesting
    List<TopicPosition> getStreamingStart(final String topic, final String cursorsStr)
            throws UnparseableCursorException, ServiceUnavailableException, InvalidCursorException {
        List<Cursor> cursors = null;
        if (cursorsStr != null) {
            try {
                cursors = jsonMapper.readValue(cursorsStr, new TypeReference<ArrayList<Cursor>>() {
                });
            } catch (final IOException ex) {
                throw new UnparseableCursorException("Incorrect syntax of X-nakadi-cursors header", ex, cursorsStr);
            }
        }
        if (null != cursors) {
            Map<String, TopicPosition> begin = null;
            final List<TopicPosition> result = new ArrayList<>();
            for (final Cursor c : cursors) {
                final TopicPosition toUse;
                if (Cursor.BEFORE_OLDEST_OFFSET.equalsIgnoreCase(c.getOffset())) {
                    if (null == begin) {
                        begin = topicRepository.loadOldestPosition(topic, false).stream()
                                .collect(Collectors.toMap(TopicPosition::getPartition, t -> t));
                    }
                    toUse = begin.get(c.getPartition());
                    if (null == toUse) {
                        throw new InvalidCursorException(CursorError.PARTITION_NOT_FOUND, c);
                    }
                } else {
                    if (null == c.getPartition()) {
                        throw new InvalidCursorException(CursorError.NULL_PARTITION, c);
                    } else if (null == c.getOffset()) {
                        throw new InvalidCursorException(CursorError.NULL_OFFSET, c);
                    }
                    toUse = new TopicPosition(topic, c.getPartition(), c.getOffset());
                }
                result.add(toUse);
            }
            if (result.isEmpty()) {
                throw new InvalidCursorException(CursorError.INVALID_FORMAT);
            }
            return result;
        } else {
            // if no cursors provided - read from the newest available events
            return topicRepository.loadNewestPosition(topic);
        }
    }

    @RequestMapping(value = "/event-types/{name}/events", method = RequestMethod.GET)
    public StreamingResponseBody streamEvents(
            @PathVariable("name") final String eventTypeName,
            @Nullable @RequestParam(value = "batch_limit", required = false) final Integer batchLimit,
            @Nullable @RequestParam(value = "stream_limit", required = false) final Integer streamLimit,
            @Nullable @RequestParam(value = "batch_flush_timeout", required = false) final Integer batchTimeout,
            @Nullable @RequestParam(value = "stream_timeout", required = false) final Integer streamTimeout,
            @Nullable
            @RequestParam(value = "stream_keep_alive_limit", required = false) final Integer streamKeepAliveLimit,
            @Nullable @RequestHeader(name = "X-nakadi-cursors", required = false) final String cursorsStr,
            final HttpServletRequest request, final HttpServletResponse response, final Client client)
            throws IOException {

        return outputStream -> {

            if (blacklistService.isConsumptionBlocked(eventTypeName, client.getClientId())) {
                writeProblemResponse(response, outputStream,
                        Problem.valueOf(Response.Status.FORBIDDEN, "Application or event type is blocked"));
                return;
            }

            final AtomicBoolean connectionReady = closedConnectionsCrutch.listenForConnectionClose(request);
            Counter consumerCounter = null;
            EventStream eventStream = null;

            List<ConnectionSlot> connectionSlots = ImmutableList.of();

            try {
                @SuppressWarnings("UnnecessaryLocalVariable")
                final EventType eventType = eventTypeRepository.findByName(eventTypeName);
                final String topic = eventType.getTopic();

                client.checkScopes(eventType.getReadScopes());

                // validate parameters
                if (!topicRepository.topicExists(topic)) {
                    writeProblemResponse(response, outputStream, INTERNAL_SERVER_ERROR, "topic is absent in kafka");
                    return;
                }
                final EventStreamConfig streamConfig = EventStreamConfig.builder()
                        .withBatchLimit(batchLimit)
                        .withStreamLimit(streamLimit)
                        .withBatchTimeout(batchTimeout)
                        .withStreamTimeout(streamTimeout)
                        .withStreamKeepAliveLimit(streamKeepAliveLimit)
                        .withEtName(eventTypeName)
                        .withConsumingAppId(client.getClientId())
                        .withCursors(getStreamingStart(topic, cursorsStr))
                        .build();

                // acquire connection slots to limit the number of simultaneous connections from one client
                if (featureToggleService.isFeatureEnabled(LIMIT_CONSUMERS_NUMBER)) {
                    final List<String> partitions = streamConfig.getCursors().stream()
                            .map(TopicPosition::getPartition)
                            .collect(Collectors.toList());
                    connectionSlots = consumerLimitingService.acquireConnectionSlots(
                            client.getClientId(), eventTypeName, partitions);
                }

                consumerCounter = metricRegistry.counter(metricNameFor(eventTypeName, CONSUMERS_COUNT_METRIC_NAME));
                consumerCounter.inc();

                response.setStatus(HttpStatus.OK.value());
                response.setContentType("application/x-json-stream");
                final EventConsumer eventConsumer = topicRepository.createEventConsumer(streamConfig.getCursors());
                eventStream = eventStreamFactory.createEventStream(
                        outputStream, eventConsumer, streamConfig, blacklistService);

                outputStream.flush(); // Flush status code to client

                eventStream.streamEvents(connectionReady);
            } catch (final UnparseableCursorException e) {
                LOG.debug("Incorrect syntax of X-nakadi-cursors header: {}. Respond with BAD_REQUEST.",
                        e.getCursors(), e);
                writeProblemResponse(response, outputStream, BAD_REQUEST,
                        "incorrect syntax of X-nakadi-cursors header");

            } catch (final NoSuchEventTypeException e) {
                writeProblemResponse(response, outputStream, NOT_FOUND, "topic not found");
            } catch (final NoConnectionSlotsException e) {
                LOG.debug("Connection creation failed due to exceeding max connection count");
                writeProblemResponse(response, outputStream, e.asProblem());
            } catch (final NakadiException e) {
                LOG.error("Error while trying to stream events.", e);
                writeProblemResponse(response, outputStream, e.asProblem());
            } catch (final InvalidCursorException e) {
                writeProblemResponse(response, outputStream, PRECONDITION_FAILED, e.getMessage());
            } catch (final IllegalScopeException e) {
                writeProblemResponse(response, outputStream, FORBIDDEN, e.getMessage());
            } catch (final Exception e) {
                LOG.error("Error while trying to stream events. Respond with INTERNAL_SERVER_ERROR.", e);
                writeProblemResponse(response, outputStream, INTERNAL_SERVER_ERROR, e.getMessage());
            } finally {
                connectionReady.set(false);
                consumerLimitingService.releaseConnectionSlots(connectionSlots);
                if (consumerCounter != null) {
                    consumerCounter.dec();
                }
                if (eventStream != null) {
                    eventStream.close();
                }
                try {
                    outputStream.flush();
                } finally {
                    outputStream.close();
                }
            }
        };
    }

    private void writeProblemResponse(final HttpServletResponse response, final OutputStream outputStream,
                                      final Response.StatusType statusCode, final String message) throws IOException {
        writeProblemResponse(response, outputStream, Problem.valueOf(statusCode, message));
    }

    private void writeProblemResponse(final HttpServletResponse response, final OutputStream outputStream,
                                      final Problem problem) throws IOException {
        response.setStatus(problem.getStatus().getStatusCode());
        response.setContentType("application/problem+json");
        jsonMapper.writer().writeValue(outputStream, problem);
    }
}
