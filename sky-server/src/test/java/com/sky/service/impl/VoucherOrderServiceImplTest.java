package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.SeckillVoucher;
import com.sky.exception.BaseException;
import com.sky.mapper.SeckillVoucherMapper;
import com.sky.mapper.VoucherOrderMapper;
import com.sky.utils.RedisIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private TransactionTemplate transactionTemplate;

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
                eq(ORDER_ID.toString())
        );
        verify(voucherOrderMapper, never()).insert(any());
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
                anyString()
        )).thenReturn(luaResult);
    }

    private SeckillVoucher voucher(LocalDateTime beginTime, LocalDateTime endTime) {
        return SeckillVoucher.builder()
                .voucherId(VOUCHER_ID)
                .stock(10)
                .beginTime(beginTime)
                .endTime(endTime)
                .build();
    }
}
