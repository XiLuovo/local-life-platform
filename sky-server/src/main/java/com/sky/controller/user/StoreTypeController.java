package com.sky.controller.user;

import com.sky.entity.ShopType;
import com.sky.result.Result;
import com.sky.service.StoreTypeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/store-type")
@Api(tags = "Store type APIs")
public class StoreTypeController {

    @Autowired
    private StoreTypeService storeTypeService;

    @GetMapping("/list")
    @ApiOperation("Query store type list")
    public Result<List<ShopType>> queryTypeList() {
        return Result.success(storeTypeService.listAll());
    }
}
