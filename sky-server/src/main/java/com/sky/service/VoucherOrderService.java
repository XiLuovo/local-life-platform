package com.sky.service;

import com.sky.vo.VoucherOrderStatusVO;

public interface VoucherOrderService {

    Long seckillVoucher(Long voucherId);

    VoucherOrderStatusVO getOrderStatus(Long orderId);
}
