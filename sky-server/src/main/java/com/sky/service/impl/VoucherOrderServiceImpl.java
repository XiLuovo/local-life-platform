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
import com.sky.vo.VoucherOrderStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class VoucherOrderServiceImpl implements VoucherOrderService {

    private static final String ORDER_GROUP = "g1";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STOCK_ACTION_NONE = "NONE";
    private static final String STOCK_ACTION_RESTORE = "RESTORE";
    private static final String STOCK_ACTION_ZERO = "ZERO";

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("seckill.lua");
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = script("seckill_complete.lua");
    private static final DefaultRedisScript<Long> FAIL_SCRIPT = script("seckill_fail.lua");
    private static final DefaultRedisScript<Long> MALFORMED_SCRIPT = script("seckill_malformed.lua");

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

    @Value("${sky.seckill.consumer-name:${HOSTNAME:local}}")
    private String consumerName;
    @Value("${sky.seckill.max-retries:3}")
    private int maxRetries;
    @Value("${sky.seckill.retry-delay-ms:2000}")
    private long retryDelayMs;
    @Value("${sky.seckill.claim-idle-ms:30000}")
    private long claimIdleMs;
    @Value("${sky.seckill.block-timeout-ms:2000}")
    private long blockTimeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService orderExecutor;

    @PostConstruct
    private void init() {
        consumerName = createConsumerName(consumerName);
        maxRetries = Math.max(1, maxRetries);
        retryDelayMs = Math.max(100, retryDelayMs);
        claimIdleMs = Math.max(retryDelayMs, claimIdleMs);
        blockTimeoutMs = Math.max(100, blockTimeoutMs);

        initStreamGroup();
        running.set(true);
        orderExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voucher-order-" + consumerName);
            thread.setDaemon(true);
            return thread;
        });
        orderExecutor.submit(new VoucherOrderHandler());
        log.info("Voucher order consumer started, consumerName={}", consumerName);
    }

    @PreDestroy
    private void destroy() {
        running.set(false);
        if (orderExecutor == null) {
            return;
        }
        orderExecutor.shutdownNow();
        try {
            if (!orderExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Voucher order consumer did not stop within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
                String.valueOf(orderId),
                String.valueOf(RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS)
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

    @Override
    public VoucherOrderStatusVO getOrderStatus(Long orderId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException(MessageConstant.USER_NOT_LOGIN);
        }
        if (orderId == null) {
            throw new BaseException(MessageConstant.SECKILL_ORDER_NOT_FOUND);
        }

        Map<Object, Object> cachedStatus = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.SECKILL_ORDER_STATUS_KEY + orderId);
        if (cachedStatus != null && !cachedStatus.isEmpty()) {
            String ownerId = valueOf(cachedStatus.get("userId"));
            if (!userId.toString().equals(ownerId)) {
                throw new BaseException(MessageConstant.SECKILL_ORDER_NOT_FOUND);
            }

            String status = valueOf(cachedStatus.get("status"));
            if (!STATUS_PENDING.equals(status) && !STATUS_SUCCESS.equals(status) && !STATUS_FAILED.equals(status)) {
                throw new BaseException("秒杀订单状态异常");
            }
            return VoucherOrderStatusVO.builder()
                    .orderId(orderId)
                    .status(status)
                    .message(emptyToNull(valueOf(cachedStatus.get("message"))))
                    .build();
        }

        VoucherOrder voucherOrder = voucherOrderMapper.getByIdAndUserId(orderId, userId);
        if (voucherOrder == null) {
            throw new BaseException(MessageConstant.SECKILL_ORDER_NOT_FOUND);
        }
        return VoucherOrderStatusVO.builder()
                .orderId(orderId)
                .status(STATUS_SUCCESS)
                .build();
    }

    private void initStreamGroup() {
        byte[] rawStreamKey = stringRedisTemplate.getStringSerializer()
                .serialize(RedisConstants.STREAM_ORDERS_KEY);
        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            rawStreamKey,
                            ORDER_GROUP,
                            ReadOffset.from("0-0"),
                            true
                    )
            );
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw new IllegalStateException("Failed to initialize voucher order stream group", e);
            }
        }
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    if (processOwnPendingRecord()) {
                        continue;
                    }
                    if (claimAndProcessStaleRecord()) {
                        continue;
                    }

                    StreamOperations<String, String, String> operations = stringRedisTemplate.opsForStream();
                    List<MapRecord<String, String, String>> records = operations.read(
                            Consumer.from(ORDER_GROUP, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofMillis(blockTimeoutMs)),
                            StreamOffset.create(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                    );
                    if (records != null && !records.isEmpty()) {
                        processRecord(records.get(0));
                    }
                } catch (Exception e) {
                    if (!running.get() || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    log.error("Voucher order consumer loop failed, consumerName={}", consumerName, e);
                }
            }
        }
    }

    private boolean processOwnPendingRecord() {
        StreamOperations<String, String, String> operations = stringRedisTemplate.opsForStream();
        PendingMessages pendingMessages = operations.pending(
                RedisConstants.STREAM_ORDERS_KEY,
                Consumer.from(ORDER_GROUP, consumerName),
                Range.unbounded(),
                1
        );
        if (pendingMessages.isEmpty()) {
            return false;
        }

        PendingMessage pending = pendingMessages.get(0);
        if (pending.getElapsedTimeSinceLastDelivery().toMillis() < retryDelayMs) {
            return false;
        }

        List<MapRecord<String, String, String>> records = operations.read(
                Consumer.from(ORDER_GROUP, consumerName),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.from("0"))
        );
        if (records == null || records.isEmpty()) {
            return false;
        }
        processRecord(records.get(0));
        return true;
    }

    private boolean claimAndProcessStaleRecord() {
        StreamOperations<String, String, String> operations = stringRedisTemplate.opsForStream();
        PendingMessages pendingMessages = operations.pending(
                RedisConstants.STREAM_ORDERS_KEY,
                ORDER_GROUP,
                Range.unbounded(),
                10
        );
        List<RecordId> staleIds = new ArrayList<>();
        for (PendingMessage pending : pendingMessages) {
            if (!consumerName.equals(pending.getConsumerName())
                    && pending.getElapsedTimeSinceLastDelivery().toMillis() >= claimIdleMs) {
                staleIds.add(pending.getId());
                break;
            }
        }
        if (staleIds.isEmpty()) {
            return false;
        }

        byte[] rawStreamKey = stringRedisTemplate.getStringSerializer()
                .serialize(RedisConstants.STREAM_ORDERS_KEY);
        List<ByteRecord> claimedRecords = stringRedisTemplate.execute(
                (RedisCallback<List<ByteRecord>>) connection -> connection.streamCommands().xClaim(
                        rawStreamKey,
                        ORDER_GROUP,
                        consumerName,
                        Duration.ofMillis(claimIdleMs),
                        staleIds.toArray(new RecordId[0])
                )
        );
        if (claimedRecords == null || claimedRecords.isEmpty()) {
            return false;
        }

        for (ByteRecord byteRecord : claimedRecords) {
            processRecord(byteRecord.deserialize(stringRedisTemplate.getStringSerializer()));
        }
        return true;
    }

    private void processRecord(MapRecord<String, String, String> record) {
        VoucherOrder voucherOrder;
        try {
            voucherOrder = mapToVoucherOrder(record.getValue());
        } catch (Exception e) {
            moveMalformedRecordToDeadLetter(record, e);
            return;
        }

        try {
            OrderCreationResult result = handleVoucherOrder(voucherOrder);
            if (!OrderCreationResult.SUCCESS.equals(result)
                    && voucherOrderMapper.getByIdAndUserId(
                    voucherOrder.getId(), voucherOrder.getUserId()) != null) {
                result = OrderCreationResult.SUCCESS;
            }
            if (OrderCreationResult.SUCCESS.equals(result)) {
                finalizeSuccess(record, voucherOrder);
            } else if (OrderCreationResult.DUPLICATE.equals(result)) {
                finalizeFailure(record, voucherOrder, STOCK_ACTION_RESTORE, false,
                        "Duplicate voucher order", true);
            } else {
                finalizeFailure(record, voucherOrder, STOCK_ACTION_ZERO, true,
                        "Database stock is exhausted", true);
            }
        } catch (Exception e) {
            handleProcessingFailure(record, voucherOrder, e);
        }
    }

    private void handleProcessingFailure(MapRecord<String, String, String> record,
                                         VoucherOrder voucherOrder,
                                         Exception processingException) {
        String retryKey = RedisConstants.SECKILL_ORDER_RETRY_KEY + record.getId().getValue();
        Long attempts = stringRedisTemplate.opsForValue().increment(retryKey);
        stringRedisTemplate.expire(
                retryKey,
                RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        long currentAttempt = attempts == null ? 1L : attempts;
        if (currentAttempt < maxRetries) {
            log.warn("Voucher order processing will retry, orderId={}, attempt={}/{}",
                    voucherOrder.getId(), currentAttempt, maxRetries, processingException);
            return;
        }

        log.error("Voucher order processing reached retry limit, orderId={}, attempts={}",
                voucherOrder.getId(), currentAttempt, processingException);
        finalizeAfterRetryLimit(record, voucherOrder);
    }

    private void finalizeAfterRetryLimit(MapRecord<String, String, String> record,
                                         VoucherOrder voucherOrder) {
        try {
            VoucherOrder exactOrder = voucherOrderMapper.getByIdAndUserId(
                    voucherOrder.getId(), voucherOrder.getUserId());
            if (exactOrder != null) {
                finalizeSuccess(record, voucherOrder);
                return;
            }

            VoucherOrder existingOrder = voucherOrderMapper.getByUserAndVoucher(
                    voucherOrder.getUserId(), voucherOrder.getVoucherId());
            if (existingOrder != null) {
                finalizeFailure(record, voucherOrder, STOCK_ACTION_RESTORE, false,
                        "Duplicate voucher order", true);
                return;
            }

            finalizeFailure(record, voucherOrder, STOCK_ACTION_NONE, false,
                    "Manual review required", true);
        } catch (Exception stateCheckException) {
            log.error("Unable to confirm database state after retry limit, orderId={}",
                    voucherOrder.getId(), stateCheckException);
            finalizeFailure(record, voucherOrder, STOCK_ACTION_NONE, false,
                    "Manual review required", true);
        }
    }

    private VoucherOrder mapToVoucherOrder(Map<String, String> value) {
        return VoucherOrder.builder()
                .id(Long.valueOf(value.get("id")))
                .userId(Long.valueOf(value.get("userId")))
                .voucherId(Long.valueOf(value.get("voucherId")))
                .build();
    }

    private OrderCreationResult handleVoucherOrder(VoucherOrder voucherOrder) {
        OrderCreationResult result = transactionTemplate.execute(status -> createVoucherOrder(voucherOrder));
        if (result == null) {
            throw new BaseException("Voucher order transaction returned no result");
        }
        return result;
    }

    private OrderCreationResult createVoucherOrder(VoucherOrder voucherOrder) {
        VoucherOrder exactOrder = voucherOrderMapper.getByIdAndUserId(
                voucherOrder.getId(), voucherOrder.getUserId());
        if (exactOrder != null) {
            return OrderCreationResult.SUCCESS;
        }

        VoucherOrder existingOrder = voucherOrderMapper.getByUserAndVoucher(
                voucherOrder.getUserId(), voucherOrder.getVoucherId());
        if (existingOrder != null) {
            return OrderCreationResult.DUPLICATE;
        }

        int updated = seckillVoucherMapper.deductStock(voucherOrder.getVoucherId());
        if (updated == 0) {
            return OrderCreationResult.OUT_OF_STOCK;
        }

        LocalDateTime now = LocalDateTime.now();
        voucherOrder.setPayType(3);
        voucherOrder.setStatus(2);
        voucherOrder.setCreateTime(now);
        voucherOrder.setPayTime(now);
        voucherOrder.setUpdateTime(now);
        voucherOrderMapper.insert(voucherOrder);
        return OrderCreationResult.SUCCESS;
    }

    private void finalizeSuccess(MapRecord<String, String, String> record,
                                 VoucherOrder voucherOrder) {
        Long result = stringRedisTemplate.execute(
                COMPLETE_SCRIPT,
                Collections.emptyList(),
                voucherOrder.getId().toString(),
                voucherOrder.getUserId().toString(),
                voucherOrder.getVoucherId().toString(),
                record.getId().getValue(),
                String.valueOf(RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS)
        );
        if (result == null) {
            throw new BaseException("Failed to finalize voucher order success");
        }
    }

    private void finalizeFailure(MapRecord<String, String, String> record,
                                 VoucherOrder voucherOrder,
                                 String stockAction,
                                 boolean releaseUser,
                                 String reason,
                                 boolean writeDeadLetter) {
        Long result = stringRedisTemplate.execute(
                FAIL_SCRIPT,
                Collections.emptyList(),
                voucherOrder.getVoucherId().toString(),
                voucherOrder.getUserId().toString(),
                voucherOrder.getId().toString(),
                record.getId().getValue(),
                stockAction,
                releaseUser ? "1" : "0",
                reason,
                String.valueOf(RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS),
                writeDeadLetter ? "1" : "0"
        );
        if (result == null) {
            throw new BaseException("Failed to finalize voucher order failure");
        }
    }

    private void moveMalformedRecordToDeadLetter(MapRecord<String, String, String> record,
                                                  Exception exception) {
        String reason = "Malformed message: " + exception.getClass().getSimpleName();
        Long result = stringRedisTemplate.execute(
                MALFORMED_SCRIPT,
                Collections.emptyList(),
                record.getId().getValue(),
                reason,
                record.getValue().toString(),
                String.valueOf(RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS)
        );
        if (result == null) {
            throw new BaseException("Failed to move malformed voucher order message to DLQ");
        }
        log.error("Malformed voucher order message moved to DLQ, recordId={}",
                record.getId().getValue(), exception);
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private static String createConsumerName(String configuredPrefix) {
        String consumerPrefix = StringUtils.hasText(configuredPrefix) ? configuredPrefix : "consumer";
        return consumerPrefix + "-" + UUID.randomUUID();
    }

    private static String valueOf(Object value) {
        return value == null ? null : value.toString();
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private enum OrderCreationResult {
        SUCCESS,
        DUPLICATE,
        OUT_OF_STOCK
    }
}
