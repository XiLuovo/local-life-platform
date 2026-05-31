package com.sky.service;

import com.sky.entity.Voucher;

import java.util.List;

public interface VoucherService {

    void addVoucher(Voucher voucher);

    void addSeckillVoucher(Voucher voucher);

    List<Voucher> queryVoucherOfShop(Long shopId);
}
