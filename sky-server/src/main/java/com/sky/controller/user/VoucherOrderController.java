package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.VoucherOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/voucher-order")
@Api(tags = "Voucher order APIs")
public class VoucherOrderController {

    @Autowired
    private VoucherOrderService voucherOrderService;

    @PostMapping("/seckill/{id}")
    @ApiOperation("Seckill a voucher")
    public Result<Long> seckillVoucher(@PathVariable("id") Long voucherId) {
        return Result.success(voucherOrderService.seckillVoucher(voucherId));
    }
}
