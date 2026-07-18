package com.sky.metrics;

import com.sky.constant.RedisConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class SeckillMetrics {

    private static final String ORDER_GROUP = "g1";

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<AdmissionOutcome, Counter> admissionCounters =
            new EnumMap<>(AdmissionOutcome.class);
    private final Map<AdmissionOutcome, Timer> admissionTimers =
            new EnumMap<>(AdmissionOutcome.class);
    private final Map<ProcessingOutcome, Counter> processingCounters =
            new EnumMap<>(ProcessingOutcome.class);
    private final Map<ProcessingOutcome, Timer> processingTimers =
            new EnumMap<>(ProcessingOutcome.class);
    private final Map<StreamEvent, Counter> streamEventCounters =
            new EnumMap<>(StreamEvent.class);
    private final AtomicLong pendingMessages = new AtomicLong();
    private final AtomicLong deadLetterMessages = new AtomicLong();

    public SeckillMetrics(MeterRegistry meterRegistry,
                          StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;

        for (AdmissionOutcome outcome : AdmissionOutcome.values()) {
            admissionCounters.put(outcome, Counter.builder("sky.seckill.admission.requests")
                    .description("Seckill admission requests by outcome")
                    .tag("outcome", outcome.value)
                    .register(meterRegistry));
            admissionTimers.put(outcome, Timer.builder("sky.seckill.admission.duration")
                    .description("Seckill admission latency by outcome")
                    .tag("outcome", outcome.value)
                    .register(meterRegistry));
        }

        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            processingCounters.put(outcome, Counter.builder("sky.seckill.processing.records")
                    .description("Redis Stream voucher-order records by processing outcome")
                    .tag("outcome", outcome.value)
                    .register(meterRegistry));
            processingTimers.put(outcome, Timer.builder("sky.seckill.processing.duration")
                    .description("Redis Stream voucher-order processing latency by outcome")
                    .tag("outcome", outcome.value)
                    .register(meterRegistry));
        }

        for (StreamEvent event : StreamEvent.values()) {
            streamEventCounters.put(event, Counter.builder("sky.seckill.stream.events")
                    .description("Seckill Redis Stream operational events")
                    .tag("event", event.value)
                    .register(meterRegistry));
        }

        Gauge.builder("sky.seckill.stream.pending", pendingMessages, AtomicLong::get)
                .description("Current pending messages in the seckill consumer group")
                .register(meterRegistry);
        Gauge.builder("sky.seckill.stream.dlq.size", deadLetterMessages, AtomicLong::get)
                .description("Current messages in the seckill dead-letter stream")
                .register(meterRegistry);
    }

    public void recordAdmission(AdmissionOutcome outcome, long durationNanos) {
        try {
            admissionCounters.get(outcome).increment();
            admissionTimers.get(outcome).record(
                    Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("Unable to record seckill admission metric, outcome={}", outcome.value, e);
        }
    }

    public void recordProcessing(ProcessingOutcome outcome, long durationNanos) {
        try {
            processingCounters.get(outcome).increment();
            processingTimers.get(outcome).record(
                    Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("Unable to record seckill processing metric, outcome={}", outcome.value, e);
        }
    }

    public void recordStreamEvent(StreamEvent event, long amount) {
        if (amount <= 0) {
            return;
        }
        try {
            streamEventCounters.get(event).increment(amount);
        } catch (RuntimeException e) {
            log.warn("Unable to record seckill stream metric, event={}", event.value, e);
        }
    }

    @Scheduled(
            initialDelayString = "${sky.seckill.metrics-refresh-ms:5000}",
            fixedDelayString = "${sky.seckill.metrics-refresh-ms:5000}"
    )
    void refreshStreamState() {
        try {
            StreamOperations<String, String, String> operations =
                    stringRedisTemplate.opsForStream();
            PendingMessagesSummary pending = operations.pending(
                    RedisConstants.STREAM_ORDERS_KEY, ORDER_GROUP);
            Long dlqSize = operations.size(RedisConstants.STREAM_ORDERS_DLQ_KEY);
            pendingMessages.set(pending == null ? 0L : pending.getTotalPendingMessages());
            deadLetterMessages.set(dlqSize == null ? 0L : dlqSize);
        } catch (RuntimeException e) {
            recordStreamEvent(StreamEvent.STATE_REFRESH_ERROR, 1);
            log.warn("Unable to refresh seckill Stream gauges", e);
        }
    }

    public enum AdmissionOutcome {
        ACCEPTED("accepted"),
        UNAUTHENTICATED("unauthenticated"),
        VOUCHER_NOT_FOUND("voucher_not_found"),
        NOT_STARTED("not_started"),
        ENDED("ended"),
        OUT_OF_STOCK("out_of_stock"),
        DUPLICATE("duplicate"),
        ERROR("error");

        private final String value;

        AdmissionOutcome(String value) {
            this.value = value;
        }
    }

    public enum ProcessingOutcome {
        CREATED("created"),
        IDEMPOTENT_SUCCESS("idempotent_success"),
        DUPLICATE("duplicate"),
        DATABASE_STOCK_EXHAUSTED("database_stock_exhausted"),
        RETRY("retry"),
        MANUAL_REVIEW("manual_review"),
        MALFORMED("malformed"),
        ERROR("error");

        private final String value;

        ProcessingOutcome(String value) {
            this.value = value;
        }
    }

    public enum StreamEvent {
        CLAIMED("claimed"),
        RETRY_EXHAUSTED("retry_exhausted"),
        DLQ_DUPLICATE("dlq_duplicate"),
        DLQ_DATABASE_STOCK("dlq_database_stock"),
        DLQ_MANUAL_REVIEW("dlq_manual_review"),
        DLQ_MALFORMED("dlq_malformed"),
        CONSUMER_ERROR("consumer_error"),
        STATE_REFRESH_ERROR("state_refresh_error");

        private final String value;

        StreamEvent(String value) {
            this.value = value;
        }
    }
}
