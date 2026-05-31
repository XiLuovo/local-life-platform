package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.entity.SeckillVoucher;
import com.sky.entity.VoucherOrder;
import com.sky.exception.BaseException;
import com.sky.mapper.SeckillVoucherMapper;
import com.sky.mapper.VoucherOrderMapper;
import com.sky.service.VoucherOrderService;
import com.sky.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class VoucherOrderServiceImpl implements VoucherOrderService {

    private static final String ORDER_GROUP = "g1";
    private static final String ORDER_CONSUMER = "c1";
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private VoucherOrderMapper voucherOrderMapper;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    private void init() {
        initStreamGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy() {
        SECKILL_ORDER_EXECUTOR.shutdown();
    }

    @Override
    public Long seckillVoucher(Long voucherId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException(MessageConstant.USER_NOT_LOGIN);
        }

        SeckillVoucher seckillVoucher = seckillVoucherMapper.getByVoucherId(voucherId);
        if (seckillVoucher == null) {
            throw new BaseException("Seckill voucher not found");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            throw new BaseException("Seckill has not started");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            throw new BaseException("Seckill has already ended");
        }

        stringRedisTemplate.opsForValue().setIfAbsent(
                RedisConstants.SECKILL_STOCK_KEY + voucherId,
                String.valueOf(seckillVoucher.getStock())
        );

        long orderId = redisIdWorker.nextId("voucher-order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        if (result == null) {
            throw new BaseException("Seckill request failed");
        }

        int code = result.intValue();
        if (code == 1) {
            throw new BaseException("Out of stock");
        }
        if (code == 2) {
            throw new BaseException("Duplicate voucher order is not allowed");
        }
        return orderId;
    }

    private void initStreamGroup() {
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.STREAM_ORDERS_KEY))) {
            stringRedisTemplate.opsForStream().add(
                    MapRecord.create(RedisConstants.STREAM_ORDERS_KEY, Collections.singletonMap("init", "0"))
            );
        }
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.latest(), ORDER_GROUP);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                log.warn("Create stream group skipped: {}", message);
            }
        }
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }

                    MapRecord<String, Object, Object> record = records.get(0);
                    VoucherOrder voucherOrder = mapToVoucherOrder(record.getValue());
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream()
                            .acknowledge(RedisConstants.STREAM_ORDERS_KEY, ORDER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("Process voucher order message failed", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.from("0"))
                    );
                    if (records == null || records.isEmpty()) {
                        break;
                    }

                    MapRecord<String, Object, Object> record = records.get(0);
                    VoucherOrder voucherOrder = mapToVoucherOrder(record.getValue());
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream()
                            .acknowledge(RedisConstants.STREAM_ORDERS_KEY, ORDER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("Process pending voucher order message failed", e);
                    break;
                }
            }
        }
    }

    private VoucherOrder mapToVoucherOrder(Map<Object, Object> value) {
        return VoucherOrder.builder()
                .id(Long.valueOf(value.get("id").toString()))
                .userId(Long.valueOf(value.get("userId").toString()))
                .voucherId(Long.valueOf(value.get("voucherId").toString()))
                .build();
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        transactionTemplate.executeWithoutResult(status -> createVoucherOrder(voucherOrder));
    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {
        int count = voucherOrderMapper.countByUserAndVoucher(voucherOrder.getUserId(), voucherOrder.getVoucherId());
        if (count > 0) {
            log.warn("Skip duplicate voucher order, userId={}, voucherId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId());
            return;
        }

        int updated = seckillVoucherMapper.deductStock(voucherOrder.getVoucherId());
        if (updated == 0) {
            log.warn("Skip voucher order because stock is exhausted, voucherId={}", voucherOrder.getVoucherId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        voucherOrder.setPayType(3);
        voucherOrder.setStatus(2);
        voucherOrder.setCreateTime(now);
        voucherOrder.setPayTime(now);
        voucherOrder.setUpdateTime(now);
        voucherOrderMapper.insert(voucherOrder);
    }
}
