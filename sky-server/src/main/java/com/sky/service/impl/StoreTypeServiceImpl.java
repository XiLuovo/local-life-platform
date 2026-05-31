package com.sky.service.impl;

import com.sky.entity.ShopType;
import com.sky.mapper.ShopTypeMapper;
import com.sky.service.StoreTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StoreTypeServiceImpl implements StoreTypeService {

    @Autowired
    private ShopTypeMapper shopTypeMapper;

    @Override
    public List<ShopType> listAll() {
        return shopTypeMapper.listAll();
    }
}
