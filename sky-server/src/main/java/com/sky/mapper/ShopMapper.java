package com.sky.mapper;

import com.sky.entity.Shop;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShopMapper {

    @Select("select * from tb_shop where id = #{id}")
    Shop getById(Long id);

    @Select("select * from tb_shop where type_id = #{typeId} order by sold desc, id desc")
    List<Shop> listByType(Long typeId);

    @Select("select * from tb_shop order by sold desc, id desc")
    List<Shop> listAll();

    @Select("select * from tb_shop where name like concat('%', #{name}, '%') order by sold desc, id desc")
    List<Shop> listByName(String name);
}
