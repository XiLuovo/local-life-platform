package com.sky.controller.admin;

import com.sky.entity.Voucher;
import com.sky.result.Result;
import com.sky.service.VoucherService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminVoucherController")
@RequestMapping("/admin/voucher")
@Api(tags = "Voucher admin APIs")
public class VoucherController {

    @Autowired
    private VoucherService voucherService;

    @PostMapping
    @ApiOperation("Add a normal voucher")
    public Result<Long> addVoucher(@RequestBody Voucher voucher) {
        voucherService.addVoucher(voucher);
        return Result.success(voucher.getId());
    }

    @PostMapping("/seckill")
    @ApiOperation("Add a seckill voucher")
    public Result<Long> addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.success(voucher.getId());
    }
}
