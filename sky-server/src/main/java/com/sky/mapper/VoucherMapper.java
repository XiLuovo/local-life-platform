package com.sky.mapper;

import com.sky.entity.Voucher;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VoucherMapper {

    @Insert("insert into tb_voucher (shop_id, title, sub_title, rules, pay_value, actual_value, type, status, create_time, update_time) " +
            "values (#{shopId}, #{title}, #{subTitle}, #{rules}, #{payValue}, #{actualValue}, #{type}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Voucher voucher);

    @Select("select v.*, sv.stock, sv.begin_time as beginTime, sv.end_time as endTime " +
            "from tb_voucher v left join tb_seckill_voucher sv on v.id = sv.voucher_id " +
            "where v.shop_id = #{shopId} order by v.id desc")
    List<Voucher> queryVoucherOfShop(Long shopId);
}
