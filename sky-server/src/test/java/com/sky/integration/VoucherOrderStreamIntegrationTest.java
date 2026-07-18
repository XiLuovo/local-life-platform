package com.sky.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.domain.Range;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers
@ActiveProfiles("it")
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VoucherOrderStreamIntegrationTest {

    private static final String REDIS_PASSWORD = "integration-test-password";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("sky_take_out")
            .withUsername("sky")
            .withPassword("integration-test-password")
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/seckill-it.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("sky.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("sky.datasource.host", MYSQL::getHost);
        registry.add("sky.datasource.port", MYSQL::getFirstMappedPort);
        registry.add("sky.datasource.database", MYSQL::getDatabaseName);
        registry.add("sky.datasource.username", MYSQL::getUsername);
        registry.add("sky.datasource.password", MYSQL::getPassword);
        registry.add("sky.redis.host", REDIS::getHost);
        registry.add("sky.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("sky.redis.password", () -> REDIS_PASSWORD);
        registry.add("sky.redis.database", () -> 10);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @LocalManagementPort
    private int managementPort;

    @Test
    void userCanCreateSeckillOrderAndObserveSuccess() throws Exception {
        String token = login("13800000001");
        long orderId = createSeckillOrder(token, 2L);

        JsonNode statusResponse = awaitTerminalStatus(token, orderId);

        assertEquals("SUCCESS", statusResponse.path("data").path("status").asText());
        assertEquals(orderId, statusResponse.path("data").path("orderId").asLong());

        String metrics = restTemplate.getForObject(
                managementUrl("/actuator/prometheus"), String.class);
        assertNotNull(metrics);
        assertTrue(metrics.contains("sky_seckill_admission_requests_total"));
        assertTrue(metrics.contains("outcome=\"accepted\""));
        assertTrue(metrics.contains("sky_seckill_processing_records_total"));
    }

    @Test
    void actuatorOnlyExposesHealthAndPrometheus() {
        ResponseEntity<String> health =
                restTemplate.getForEntity(managementUrl("/actuator/health"), String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertNotNull(health.getBody());
        assertTrue(health.getBody().contains("\"status\":\"UP\""));

        ResponseEntity<String> prometheus =
                restTemplate.getForEntity(
                        managementUrl("/actuator/prometheus"), String.class);
        assertEquals(HttpStatus.OK, prometheus.getStatusCode());
        assertNotNull(prometheus.getBody());
        assertTrue(prometheus.getBody().contains("jvm_memory_used_bytes"));

        ResponseEntity<String> info =
                restTemplate.getForEntity(managementUrl("/actuator/info"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, info.getStatusCode());
    }

    @Test
    void successFinalizationReturnsFirstExecutionOnlyOnce() {
        DefaultRedisScript<Long> completeScript = new DefaultRedisScript<>();
        completeScript.setLocation(new ClassPathResource("seckill_complete.lua"));
        completeScript.setResultType(Long.class);
        String suffix = String.valueOf(System.nanoTime());
        String orderId = "91" + suffix;
        String recordId = "92" + suffix + "-0";

        Long first = stringRedisTemplate.execute(
                completeScript,
                Collections.emptyList(),
                orderId,
                "93001",
                "94001",
                recordId,
                "60"
        );
        Long repeated = stringRedisTemplate.execute(
                completeScript,
                Collections.emptyList(),
                orderId,
                "93001",
                "94001",
                recordId,
                "60"
        );

        assertEquals(1L, first);
        assertEquals(0L, repeated);
        assertEquals("SUCCESS", stringRedisTemplate.opsForHash().get(
                "seckill:order:status:" + orderId, "status"));
    }

    @Test
    void sameUserCannotCreateDuplicateVoucherOrder() throws Exception {
        String token = login("13800000002");
        long firstOrderId = createSeckillOrder(token, 3L);
        JsonNode firstOrderStatus = awaitTerminalStatus(token, firstOrderId);
        assertEquals("SUCCESS", firstOrderStatus.path("data").path("status").asText());

        JsonNode duplicateResponse = postForJson(
                "/user/voucher-order/seckill/3", authenticatedEntity(token, null));
        assertEquals(0, duplicateResponse.path("code").asInt(), duplicateResponse.toString());
        assertEquals("Duplicate voucher order is not allowed",
                duplicateResponse.path("msg").asText());
    }

    @Test
    void uncertainDatabaseFailureRequiresManualReviewWithoutReleasingQualification() throws Exception {
        String token = login("13800000003");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_voucher_order_insert");
        jdbcTemplate.execute(
                "CREATE TRIGGER fail_voucher_order_insert " +
                        "BEFORE INSERT ON tb_voucher_order FOR EACH ROW " +
                        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced integration failure'"
        );
        try {
            long orderId = createSeckillOrder(token, 4L);

            JsonNode statusResponse = awaitTerminalStatus(token, orderId);

            assertEquals("FAILED", statusResponse.path("data").path("status").asText());
            assertEquals("Manual review required",
                    statusResponse.path("data").path("message").asText());

            JsonNode duplicateResponse = postForJson(
                    "/user/voucher-order/seckill/4", authenticatedEntity(token, null));
            assertEquals(0, duplicateResponse.path("code").asInt(), duplicateResponse.toString());
            assertEquals("Duplicate voucher order is not allowed",
                    duplicateResponse.path("msg").asText());
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_voucher_order_insert");
        }
    }

    @Test
    void activeConsumerClaimsMessageLeftByCrashedConsumer() throws Exception {
        String blockingUserToken = login("13800000004");
        String recoveredUserToken = login("13800000005");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS slow_voucher_order_insert");
        jdbcTemplate.execute(
                "CREATE TRIGGER slow_voucher_order_insert " +
                        "BEFORE INSERT ON tb_voucher_order FOR EACH ROW " +
                        "BEGIN IF NEW.voucher_id = 5 THEN DO SLEEP(2); END IF; END"
        );
        try {
            long blockingOrderId = createSeckillOrder(blockingUserToken, 5L);
            awaitOrderPending(blockingOrderId);
            long recoveredOrderId = createSeckillOrder(recoveredUserToken, 6L);

            List<MapRecord<String, Object, Object>> abandonedRecords =
                    stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "crashed-integration-test"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
            assertNotNull(abandonedRecords);
            assertFalse(abandonedRecords.isEmpty(),
                    "The simulated crashed consumer did not receive a Stream message");
            assertEquals(String.valueOf(recoveredOrderId),
                    abandonedRecords.get(0).getValue().get("id").toString());

            JsonNode statusResponse = awaitTerminalStatus(
                    recoveredUserToken, recoveredOrderId);

            assertEquals("SUCCESS", statusResponse.path("data").path("status").asText());
            assertFalse(isOrderPending(recoveredOrderId),
                    "The recovered order remained in the Stream pending list");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS slow_voucher_order_insert");
        }
    }

    private String login(String phone) throws Exception {
        JsonNode codeResponse = postForJson("/user/user/code?phone=" + phone, null);
        assertEquals(1, codeResponse.path("code").asInt(), codeResponse.toString());

        Map<String, String> loginPayload = new HashMap<>();
        loginPayload.put("phone", phone);
        loginPayload.put("code", "123456");
        JsonNode loginResponse = postForJson("/user/user/login", loginPayload);
        assertEquals(1, loginResponse.path("code").asInt(), loginResponse.toString());
        String token = loginResponse.path("data").path("token").asText();
        assertNotNull(token);
        assertFalse(token.isEmpty());
        return token;
    }

    private long createSeckillOrder(String token, Long voucherId) throws Exception {
        JsonNode response = postForJson(
                "/user/voucher-order/seckill/" + voucherId,
                authenticatedEntity(token, null));
        assertEquals(1, response.path("code").asInt(), response.toString());
        return response.path("data").asLong();
    }

    private JsonNode awaitTerminalStatus(String token, long orderId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        JsonNode lastResponse = null;
        while (Instant.now().isBefore(deadline)) {
            lastResponse = getForJson(
                    "/user/voucher-order/" + orderId + "/status", token);
            assertEquals(1, lastResponse.path("code").asInt(), lastResponse.toString());
            String status = lastResponse.path("data").path("status").asText();
            if (!"PENDING".equals(status)) {
                return lastResponse;
            }
            Thread.sleep(100);
        }
        fail("Seckill order did not reach a terminal status: " + lastResponse);
        return lastResponse;
    }

    private void awaitOrderPending(long orderId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (isOrderPending(orderId)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Order did not enter the Stream pending list: " + orderId);
    }

    private boolean isOrderPending(long orderId) {
        PendingMessages pendingMessages = stringRedisTemplate.opsForStream().pending(
                "stream.orders", "g1", Range.unbounded(), 20);
        for (PendingMessage pendingMessage : pendingMessages) {
            String recordId = pendingMessage.getIdAsString();
            List<MapRecord<String, Object, Object>> records =
                    stringRedisTemplate.opsForStream().range(
                            "stream.orders", Range.closed(recordId, recordId));
            if (records == null || records.isEmpty()) {
                continue;
            }
            Object messageOrderId = records.get(0).getValue().get("id");
            if (messageOrderId != null && String.valueOf(orderId).equals(messageOrderId.toString())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode postForJson(String path, Object body) throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(path, body, String.class);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode getForJson(String path, String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                path,
                org.springframework.http.HttpMethod.GET,
                authenticatedEntity(token, null),
                String.class
        );
        return objectMapper.readTree(response.getBody());
    }

    private HttpEntity<Object> authenticatedEntity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("authentication", token);
        return new HttpEntity<>(body, headers);
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
