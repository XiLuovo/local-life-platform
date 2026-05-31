package com.sky.mapper;

import com.sky.entity.ShopType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShopTypeMapper {

    @Select("select * from tb_shop_type order by sort asc, id asc")
    List<ShopType> listAll();
}
