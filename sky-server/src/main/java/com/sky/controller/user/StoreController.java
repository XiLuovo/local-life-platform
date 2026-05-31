package com.sky.controller.user;

import com.sky.entity.Shop;
import com.sky.result.Result;
import com.sky.service.StoreService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/store")
@Api(tags = "Store browsing APIs")
public class StoreController {

    @Autowired
    private StoreService storeService;

    @GetMapping("/{id}")
    @ApiOperation("Query store by id")
    public Result<Shop> queryById(@PathVariable Long id) {
        Shop shop = storeService.queryById(id);
        return shop == null ? Result.error("Store not found") : Result.success(shop);
    }

    @GetMapping("/of/type")
    @ApiOperation("Query stores by type")
    public Result<List<Shop>> queryByType(@RequestParam("typeId") Integer typeId,
                                          @RequestParam(value = "current", defaultValue = "1") Integer current,
                                          @RequestParam(value = "x", required = false) Double x,
                                          @RequestParam(value = "y", required = false) Double y) {
        return Result.success(storeService.queryByType(typeId, current, x, y));
    }

    @GetMapping("/of/name")
    @ApiOperation("Query stores by name")
    public Result<List<Shop>> queryByName(@RequestParam(value = "name", required = false) String name,
                                          @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return Result.success(storeService.queryByName(name, current));
    }
}
