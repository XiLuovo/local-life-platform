package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled order maintenance tasks.
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * Close unpaid orders after timeout.
     */
    @Scheduled(cron = "0 * * * * ? ")
    public void processTimeoutOrder() {
        log.info("定时处理超时订单：{}", LocalDateTime.now());

        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                Orders updateOrder = new Orders();
                updateOrder.setId(orders.getId());
                updateOrder.setStatus(Orders.CANCELLED);
                updateOrder.setCancelReason("订单超时，自动取消");
                updateOrder.setCancelTime(LocalDateTime.now());
                orderMapper.updateByIdAndStatus(updateOrder, Orders.PENDING_PAYMENT);
            }
        }
    }

    /**
     * Auto-complete stale delivery orders.
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void processDeliveryOrder() {
        log.info("定时处理配送中的订单：{}", LocalDateTime.now());

        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        if (ordersList != null && !ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                Orders updateOrder = new Orders();
                updateOrder.setId(orders.getId());
                updateOrder.setStatus(Orders.COMPLETED);
                updateOrder.setDeliveryTime(LocalDateTime.now());
                orderMapper.updateByIdAndStatus(updateOrder, Orders.DELIVERY_IN_PROGRESS);
            }
        }
    }
}
