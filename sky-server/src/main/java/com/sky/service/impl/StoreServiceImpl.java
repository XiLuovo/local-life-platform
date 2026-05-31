package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.PageHelper;
import com.sky.constant.RedisConstants;
import com.sky.entity.Shop;
import com.sky.mapper.ShopMapper;
import com.sky.service.StoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class StoreServiceImpl implements StoreService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Shop queryById(Long id) {
        String cacheKey = RedisConstants.CACHE_STORE_KEY + id;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cachedJson)) {
            return JSON.parseObject(cachedJson, Shop.class);
        }
        if (cachedJson != null) {
            return null;
        }

        Shop shop = shopMapper.getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(cacheKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        stringRedisTemplate.opsForValue().set(
                cacheKey,
                JSON.toJSONString(shop),
                RedisConstants.CACHE_STORE_TTL,
                TimeUnit.MINUTES
        );
        return shop;
    }

    @Override
    public List<Shop> queryByType(Integer typeId, Integer current, Double x, Double y) {
        int page = current == null || current < 1 ? 1 : current;
        if (x != null && y != null) {
            log.info("Geo sorting is not enabled yet, fallback to database paging");
        }

        PageHelper.startPage(page, DEFAULT_PAGE_SIZE);
        List<Shop> shops = shopMapper.listByType(typeId.longValue());
        return shops == null ? Collections.emptyList() : shops;
    }

    @Override
    public List<Shop> queryByName(String name, Integer current) {
        int page = current == null || current < 1 ? 1 : current;
        PageHelper.startPage(page, MAX_PAGE_SIZE);
        List<Shop> shops = StringUtils.hasText(name) ? shopMapper.listByName(name) : shopMapper.listAll();
        return shops == null ? Collections.emptyList() : shops;
    }
}
