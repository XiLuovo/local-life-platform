package com.sky.metrics;

import com.sky.constant.RedisConstants;
import com.sky.metrics.SeckillMetrics.AdmissionOutcome;
import com.sky.metrics.SeckillMetrics.ProcessingOutcome;
import com.sky.metrics.SeckillMetrics.StreamEvent;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillMetricsTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private SimpleMeterRegistry meterRegistry;
    private SeckillMetrics seckillMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        seckillMetrics = new SeckillMetrics(meterRegistry, stringRedisTemplate);
    }

    @Test
    void recordsAdmissionProcessingAndStreamEvents() {
        seckillMetrics.recordAdmission(AdmissionOutcome.ACCEPTED, 2_000_000L);
        seckillMetrics.recordProcessing(ProcessingOutcome.CREATED, 5_000_000L);
        seckillMetrics.recordStreamEvent(StreamEvent.CLAIMED, 3);

        assertEquals(1.0, meterRegistry.get("sky.seckill.admission.requests")
                .tag("outcome", "accepted").counter().count());
        assertEquals(1L, meterRegistry.get("sky.seckill.admission.duration")
                .tag("outcome", "accepted").timer().count());
        assertEquals(2.0, meterRegistry.get("sky.seckill.admission.duration")
                .tag("outcome", "accepted").timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(1.0, meterRegistry.get("sky.seckill.processing.records")
                .tag("outcome", "created").counter().count());
        assertEquals(3.0, meterRegistry.get("sky.seckill.stream.events")
                .tag("event", "claimed").counter().count());
    }

    @Test
    void refreshesPendingAndDeadLetterGauges() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.pending(RedisConstants.STREAM_ORDERS_KEY, "g1"))
                .thenReturn(new PendingMessagesSummary(
                        "g1", 4L, Range.unbounded(), Collections.emptyMap()));
        when(streamOperations.size(RedisConstants.STREAM_ORDERS_DLQ_KEY)).thenReturn(2L);

        seckillMetrics.refreshStreamState();

        assertEquals(4.0, meterRegistry.get("sky.seckill.stream.pending").gauge().value());
        assertEquals(2.0, meterRegistry.get("sky.seckill.stream.dlq.size").gauge().value());
    }

    @Test
    void recordsGaugeRefreshFailuresWithoutThrowing() {
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.pending(RedisConstants.STREAM_ORDERS_KEY, "g1"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        seckillMetrics.refreshStreamState();

        assertEquals(1.0, meterRegistry.get("sky.seckill.stream.events")
                .tag("event", "state_refresh_error").counter().count());
    }

    @Test
    void exportsPrometheusMetricNamesAndLowCardinalityLabels() {
        PrometheusMeterRegistry prometheusRegistry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheusRegistry.config()
                .commonTags("application", "local-life-platform")
                .meterFilter(new MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(
                            Meter.Id id, DistributionStatisticConfig config) {
                        if (id.getName().equals("sky.seckill.admission.duration")
                                || id.getName().equals("sky.seckill.processing.duration")) {
                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                });
        SeckillMetrics prometheusMetrics =
                new SeckillMetrics(prometheusRegistry, stringRedisTemplate);

        prometheusMetrics.recordAdmission(AdmissionOutcome.OUT_OF_STOCK, 1_000_000L);
        prometheusMetrics.recordProcessing(ProcessingOutcome.CREATED, 2_000_000L);
        prometheusMetrics.recordStreamEvent(StreamEvent.DLQ_MANUAL_REVIEW, 1);

        String scrape = prometheusRegistry.scrape();
        assertMetricHasLabels(scrape, "sky_seckill_admission_requests_total",
                "application=\"local-life-platform\"", "outcome=\"out_of_stock\"");
        assertMetricHasLabels(scrape, "sky_seckill_admission_duration_seconds_bucket",
                "application=\"local-life-platform\"", "outcome=\"out_of_stock\"");
        assertMetricHasLabels(scrape, "sky_seckill_processing_records_total",
                "application=\"local-life-platform\"", "outcome=\"created\"");
        assertMetricHasLabels(scrape, "sky_seckill_processing_duration_seconds_bucket",
                "application=\"local-life-platform\"", "outcome=\"created\"");
        assertMetricHasLabels(scrape, "sky_seckill_stream_events_total",
                "application=\"local-life-platform\"", "event=\"dlq_manual_review\"");
        assertMetricHasLabels(scrape, "sky_seckill_stream_pending",
                "application=\"local-life-platform\"");
        assertMetricHasLabels(scrape, "sky_seckill_stream_dlq_size",
                "application=\"local-life-platform\"");
    }

    private static void assertMetricHasLabels(String scrape,
                                              String metricName,
                                              String... labels) {
        for (String line : scrape.split("\\R")) {
            if (!line.startsWith(metricName + "{")) {
                continue;
            }
            boolean allLabelsPresent = true;
            for (String label : labels) {
                if (!line.contains(label)) {
                    allLabelsPresent = false;
                    break;
                }
            }
            if (allLabelsPresent) {
                return;
            }
        }
        fail("Missing Prometheus sample " + metricName
                + " with labels " + java.util.Arrays.toString(labels));
    }
}
