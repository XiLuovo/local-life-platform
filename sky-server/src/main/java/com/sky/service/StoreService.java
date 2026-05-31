package com.sky.service;

import com.sky.entity.Shop;

import java.util.List;

public interface StoreService {

    Shop queryById(Long id);

    List<Shop> queryByType(Integer typeId, Integer current, Double x, Double y);

    List<Shop> queryByName(String name, Integer current);
}
