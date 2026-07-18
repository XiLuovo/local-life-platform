package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.SeckillVoucher;
import com.sky.exception.BaseException;
import com.sky.mapper.SeckillVoucherMapper;
import com.sky.mapper.VoucherOrderMapper;
import com.sky.metrics.SeckillMetrics;
import com.sky.metrics.SeckillMetrics.AdmissionOutcome;
import com.sky.metrics.SeckillMetrics.ProcessingOutcome;
import com.sky.metrics.SeckillMetrics.StreamEvent;
import com.sky.utils.RedisIdWorker;
import com.sky.vo.VoucherOrderStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final Long VOUCHER_ID = 2001L;
    private static final Long ORDER_ID = 3001L;

    @Mock
    private SeckillVoucherMapper seckillVoucherMapper;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;
    @Mock
    private RedisIdWorker redisIdWorker;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private SeckillMetrics seckillMetrics;

    @InjectMocks
    private VoucherOrderServiceImpl voucherOrderService;

    @BeforeEach
    void setUp() {
        BaseContext.removeCurrentId();
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void shouldRejectSeckillWhenUserIsNotLoggedIn() {
        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("用户未登录", exception.getMessage());
        verifyNoInteractions(seckillVoucherMapper, redisIdWorker, stringRedisTemplate);
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.UNAUTHENTICATED), anyLong());
    }

    @Test
    void shouldRejectMissingVoucherAndRecordMetric() {
        BaseContext.setCurrentId(USER_ID);
        when(seckillVoucherMapper.getByVoucherId(VOUCHER_ID)).thenReturn(null);

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("Seckill voucher not found", exception.getMessage());
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.VOUCHER_NOT_FOUND), anyLong());
    }

    @Test
    void shouldRejectSeckillBeforeActivityBegins() {
        BaseContext.setCurrentId(USER_ID);
        when(seckillVoucherMapper.getByVoucherId(VOUCHER_ID)).thenReturn(
                voucher(LocalDateTime.now().plusMinutes(10), LocalDateTime.now().plusHours(1))
        );

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("Seckill has not started", exception.getMessage());
        verifyNoInteractions(redisIdWorker, stringRedisTemplate);
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.NOT_STARTED), anyLong());
    }

    @Test
    void shouldRejectSeckillAfterActivityEnds() {
        BaseContext.setCurrentId(USER_ID);
        when(seckillVoucherMapper.getByVoucherId(VOUCHER_ID)).thenReturn(
                voucher(LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(10))
        );

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("Seckill has already ended", exception.getMessage());
        verifyNoInteractions(redisIdWorker, stringRedisTemplate);
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.ENDED), anyLong());
    }

    @Test
    void shouldRejectSeckillWhenLuaReportsOutOfStock() {
        arrangeActiveSeckill(1L);

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("Out of stock", exception.getMessage());
        verify(redisIdWorker).nextId("voucher-order");
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.OUT_OF_STOCK), anyLong());
    }

    @Test
    void shouldRejectDuplicateVoucherOrderWhenLuaReportsDuplicate() {
        arrangeActiveSeckill(2L);

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("Duplicate voucher order is not allowed", exception.getMessage());
        verify(redisIdWorker).nextId("voucher-order");
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.DUPLICATE), anyLong());
    }

    @Test
    void shouldRejectUnexpectedLuaResultAndRecordErrorMetric() {
        arrangeActiveSeckill(99L);

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("Unexpected seckill result", exception.getMessage());
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.ERROR), anyLong());
    }

    @Test
    void shouldReturnGeneratedOrderIdWhenSeckillSucceeds() {
        arrangeActiveSeckill(0L);

        Long result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertEquals(ORDER_ID, result);
        verify(stringRedisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                eq(VOUCHER_ID.toString()),
                eq(USER_ID.toString()),
                eq(ORDER_ID.toString()),
                eq("86400")
        );
        verify(voucherOrderMapper, never()).insert(any());
        verify(seckillMetrics).recordAdmission(eq(AdmissionOutcome.ACCEPTED), anyLong());
    }

    @Test
    void shouldRejectStatusQueryWhenUserIsNotLoggedIn() {
        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.getOrderStatus(ORDER_ID)
        );

        assertEquals("用户未登录", exception.getMessage());
        verifyNoInteractions(hashOperations, voucherOrderMapper);
    }

    @Test
    void shouldReturnPendingStatusForOwnedOrder() {
        BaseContext.setCurrentId(USER_ID);
        arrangeCachedStatus(USER_ID, "PENDING", "");

        VoucherOrderStatusVO result = voucherOrderService.getOrderStatus(ORDER_ID);

        assertEquals(ORDER_ID, result.getOrderId());
        assertEquals("PENDING", result.getStatus());
        assertNull(result.getMessage());
        verify(voucherOrderMapper, never()).getByIdAndUserId(any(), any());
    }

    @Test
    void shouldReturnFailedStatusAndReasonForOwnedOrder() {
        BaseContext.setCurrentId(USER_ID);
        arrangeCachedStatus(USER_ID, "FAILED", "Order processing failed after retries");

        VoucherOrderStatusVO result = voucherOrderService.getOrderStatus(ORDER_ID);

        assertEquals("FAILED", result.getStatus());
        assertEquals("Order processing failed after retries", result.getMessage());
    }

    @Test
    void shouldHideStatusOwnedByAnotherUser() {
        BaseContext.setCurrentId(USER_ID);
        arrangeCachedStatus(9999L, "SUCCESS", "");

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.getOrderStatus(ORDER_ID)
        );

        assertEquals("秒杀订单不存在", exception.getMessage());
        verify(voucherOrderMapper, never()).getByIdAndUserId(any(), any());
    }

    @Test
    void shouldReturnSuccessFromDatabaseWhenStatusCacheIsMissing() {
        BaseContext.setCurrentId(USER_ID);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("seckill:order:status:" + ORDER_ID)).thenReturn(new HashMap<>());
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(
                com.sky.entity.VoucherOrder.builder()
                        .id(ORDER_ID)
                        .userId(USER_ID)
                        .voucherId(VOUCHER_ID)
                        .build()
        );

        VoucherOrderStatusVO result = voucherOrderService.getOrderStatus(ORDER_ID);

        assertEquals("SUCCESS", result.getStatus());
    }

    @Test
    void shouldRejectUnknownOrderWhenCacheAndDatabaseMiss() {
        BaseContext.setCurrentId(USER_ID);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("seckill:order:status:" + ORDER_ID)).thenReturn(new HashMap<>());
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(null);

        BaseException exception = assertThrows(
                BaseException.class,
                () -> voucherOrderService.getOrderStatus(ORDER_ID)
        );

        assertEquals("秒杀订单不存在", exception.getMessage());
    }

    @Test
    void shouldTreatSameDatabaseOrderAsIdempotentSuccess() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(message);

        Object result = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "createVoucherOrder", message);

        assertEquals("ALREADY_EXISTS", result.toString());
        verify(seckillVoucherMapper, never()).deductStock(any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void shouldClassifyDifferentExistingOrderAsDuplicate() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(null);
        when(voucherOrderMapper.getByUserAndVoucher(USER_ID, VOUCHER_ID)).thenReturn(
                com.sky.entity.VoucherOrder.builder()
                        .id(9999L)
                        .userId(USER_ID)
                        .voucherId(VOUCHER_ID)
                        .build()
        );

        Object result = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "createVoucherOrder", message);

        assertEquals("DUPLICATE", result.toString());
        verify(seckillVoucherMapper, never()).deductStock(any());
    }

    @Test
    void shouldClassifyDatabaseStockMismatchAsOutOfStock() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(null);
        when(voucherOrderMapper.getByUserAndVoucher(USER_ID, VOUCHER_ID)).thenReturn(null);
        when(seckillVoucherMapper.deductStock(VOUCHER_ID)).thenReturn(0);

        Object result = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "createVoucherOrder", message);

        assertEquals("OUT_OF_STOCK", result.toString());
        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void shouldInsertOrderAfterConditionalStockDeduction() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(null);
        when(voucherOrderMapper.getByUserAndVoucher(USER_ID, VOUCHER_ID)).thenReturn(null);
        when(seckillVoucherMapper.deductStock(VOUCHER_ID)).thenReturn(1);

        Object result = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "createVoucherOrder", message);

        assertEquals("CREATED", result.toString());
        verify(voucherOrderMapper).insert(message);
        assertEquals(2, message.getStatus());
    }

    @Test
    void shouldCreateUniqueConsumerNameForEachJvmInstance() {
        String first = ReflectionTestUtils.invokeMethod(
                VoucherOrderServiceImpl.class, "createConsumerName", "compose-consumer");
        String second = ReflectionTestUtils.invokeMethod(
                VoucherOrderServiceImpl.class, "createConsumerName", "compose-consumer");

        assertTrue(first.startsWith("compose-consumer-"));
        assertTrue(second.startsWith("compose-consumer-"));
        assertNotEquals(first, second);
    }

    @Test
    void shouldAvoidCompensationWhenRetryLimitDatabaseStateIsUnknown() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        Map<String, String> values = new HashMap<>();
        values.put("id", ORDER_ID.toString());
        values.put("userId", USER_ID.toString());
        values.put("voucherId", VOUCHER_ID.toString());
        MapRecord<String, String, String> record = MapRecord.create("stream.orders", values);
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(null);
        when(voucherOrderMapper.getByUserAndVoucher(USER_ID, VOUCHER_ID)).thenReturn(null);
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(1L);

        Object result = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "finalizeAfterRetryLimit", record, message);

        assertEquals(ProcessingOutcome.MANUAL_REVIEW,
                ReflectionTestUtils.invokeMethod(result, "getOutcome"));
        assertEquals(true, ReflectionTestUtils.invokeMethod(result, "isFirstFinalization"));
        verify(stringRedisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                eq(VOUCHER_ID.toString()),
                eq(USER_ID.toString()),
                eq(ORDER_ID.toString()),
                eq(record.getId().getValue()),
                eq("NONE"),
                eq("0"),
                eq("Manual review required"),
                eq("86400"),
                eq("1")
        );
        verify(seckillMetrics).recordStreamEvent(StreamEvent.DLQ_MANUAL_REVIEW, 1);
    }

    @Test
    void shouldRecordRetryExhaustedOnlyForFirstFinalization() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        Map<String, String> values = new HashMap<>();
        values.put("id", ORDER_ID.toString());
        values.put("userId", USER_ID.toString());
        values.put("voucherId", VOUCHER_ID.toString());
        MapRecord<String, String, String> record = MapRecord.create("stream.orders", values);
        ReflectionTestUtils.setField(voucherOrderService, "maxRetries", 3);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(3L, 4L);
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(null);
        when(voucherOrderMapper.getByUserAndVoucher(USER_ID, VOUCHER_ID)).thenReturn(null);
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(1L, 0L);

        Object firstOutcome = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "handleProcessingFailure", record, message,
                new BaseException("temporary failure"));
        Object repeatedOutcome = ReflectionTestUtils.invokeMethod(
                voucherOrderService, "handleProcessingFailure", record, message,
                new BaseException("temporary failure"));

        assertEquals(ProcessingOutcome.MANUAL_REVIEW, firstOutcome);
        assertEquals(ProcessingOutcome.MANUAL_REVIEW, repeatedOutcome);
        verify(seckillMetrics, times(1)).recordStreamEvent(StreamEvent.RETRY_EXHAUSTED, 1);
        verify(seckillMetrics, times(1)).recordStreamEvent(StreamEvent.DLQ_MANUAL_REVIEW, 1);
    }

    @Test
    void shouldFinalizeSuccessWhenOrderAppearsAfterOutOfStockTransaction() {
        com.sky.entity.VoucherOrder message = voucherOrder();
        Map<String, String> values = new HashMap<>();
        values.put("id", ORDER_ID.toString());
        values.put("userId", USER_ID.toString());
        values.put("voucherId", VOUCHER_ID.toString());
        MapRecord<String, String, String> record = MapRecord.create("stream.orders", values);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(voucherOrderMapper.getByIdAndUserId(ORDER_ID, USER_ID))
                .thenReturn(null, message);
        when(voucherOrderMapper.getByUserAndVoucher(USER_ID, VOUCHER_ID)).thenReturn(null);
        when(seckillVoucherMapper.deductStock(VOUCHER_ID)).thenReturn(0);
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(1L);

        ReflectionTestUtils.invokeMethod(voucherOrderService, "processRecord", record);

        verify(stringRedisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                eq(ORDER_ID.toString()),
                eq(USER_ID.toString()),
                eq(VOUCHER_ID.toString()),
                eq(record.getId().getValue()),
                eq("86400")
        );
        verify(seckillMetrics).recordProcessing(
                eq(ProcessingOutcome.IDEMPOTENT_SUCCESS), anyLong());
    }

    private void arrangeActiveSeckill(Long luaResult) {
        BaseContext.setCurrentId(USER_ID);
        when(seckillVoucherMapper.getByVoucherId(VOUCHER_ID)).thenReturn(
                voucher(LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(10))
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisIdWorker.nextId("voucher-order")).thenReturn(ORDER_ID);
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(luaResult);
    }

    private void arrangeCachedStatus(Long ownerId, String status, String message) {
        Map<Object, Object> values = new HashMap<>();
        values.put("userId", ownerId.toString());
        values.put("voucherId", VOUCHER_ID.toString());
        values.put("status", status);
        values.put("message", message);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("seckill:order:status:" + ORDER_ID)).thenReturn(values);
    }

    private SeckillVoucher voucher(LocalDateTime beginTime, LocalDateTime endTime) {
        return SeckillVoucher.builder()
                .voucherId(VOUCHER_ID)
                .stock(10)
                .beginTime(beginTime)
                .endTime(endTime)
                .build();
    }

    private com.sky.entity.VoucherOrder voucherOrder() {
        return com.sky.entity.VoucherOrder.builder()
                .id(ORDER_ID)
                .userId(USER_ID)
                .voucherId(VOUCHER_ID)
                .build();
    }
}
