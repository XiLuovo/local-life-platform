package com.sky.controller.user;

import com.sky.entity.Voucher;
import com.sky.result.Result;
import com.sky.service.VoucherService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userVoucherController")
@RequestMapping("/user/voucher")
@Api(tags = "User voucher APIs")
public class VoucherController {

    @Autowired
    private VoucherService voucherService;

    @GetMapping("/list/{shopId}")
    @ApiOperation("Query vouchers of a store")
    public Result<List<Voucher>> queryVoucherOfShop(@PathVariable Long shopId) {
        return Result.success(voucherService.queryVoucherOfShop(shopId));
    }
}
