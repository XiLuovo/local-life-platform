package com.sky.mapper;

import com.sky.entity.SeckillVoucher;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillVoucherMapper {

    @Insert("insert into tb_seckill_voucher (voucher_id, stock, create_time, begin_time, end_time, update_time) " +
            "values (#{voucherId}, #{stock}, #{createTime}, #{beginTime}, #{endTime}, #{updateTime})")
    int insert(SeckillVoucher seckillVoucher);

    @Select("select * from tb_seckill_voucher where voucher_id = #{voucherId}")
    SeckillVoucher getByVoucherId(Long voucherId);

    @Update("update tb_seckill_voucher set stock = stock - 1, update_time = now() where voucher_id = #{voucherId} and stock > 0")
    int deductStock(Long voucherId);
}
