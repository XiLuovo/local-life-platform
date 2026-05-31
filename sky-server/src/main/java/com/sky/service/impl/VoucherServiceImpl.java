package com.sky.service.impl;

import com.sky.constant.RedisConstants;
import com.sky.entity.SeckillVoucher;
import com.sky.entity.Voucher;
import com.sky.mapper.SeckillVoucherMapper;
import com.sky.mapper.VoucherMapper;
import com.sky.service.VoucherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class VoucherServiceImpl implements VoucherService {

    @Autowired
    private VoucherMapper voucherMapper;
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public void addVoucher(Voucher voucher) {
        LocalDateTime now = LocalDateTime.now();
        voucher.setStatus(voucher.getStatus() == null ? 1 : voucher.getStatus());
        voucher.setCreateTime(now);
        voucher.setUpdateTime(now);
        voucherMapper.insert(voucher);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        addVoucher(voucher);
        LocalDateTime now = LocalDateTime.now();
        SeckillVoucher seckillVoucher = SeckillVoucher.builder()
                .voucherId(voucher.getId())
                .stock(voucher.getStock())
                .createTime(now)
                .beginTime(voucher.getBeginTime())
                .endTime(voucher.getEndTime())
                .updateTime(now)
                .build();
        seckillVoucherMapper.insert(seckillVoucher);
        stringRedisTemplate.opsForValue().set(
                RedisConstants.SECKILL_STOCK_KEY + voucher.getId(),
                String.valueOf(voucher.getStock())
        );
    }

    @Override
    public List<Voucher> queryVoucherOfShop(Long shopId) {
        return voucherMapper.queryVoucherOfShop(shopId);
    }
}
