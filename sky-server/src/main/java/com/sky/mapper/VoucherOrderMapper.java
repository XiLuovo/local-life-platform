package com.sky.mapper;

import com.sky.entity.VoucherOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VoucherOrderMapper {

    @Select("select count(1) from tb_voucher_order where user_id = #{userId} and voucher_id = #{voucherId}")
    int countByUserAndVoucher(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("select * from tb_voucher_order where user_id = #{userId} and voucher_id = #{voucherId}")
    VoucherOrder getByUserAndVoucher(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("select * from tb_voucher_order where id = #{orderId} and user_id = #{userId}")
    VoucherOrder getByIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Insert("insert into tb_voucher_order (id, user_id, voucher_id, pay_type, status, create_time, pay_time, use_time, refund_time, update_time) " +
            "values (#{id}, #{userId}, #{voucherId}, #{payType}, #{status}, #{createTime}, #{payTime}, #{useTime}, #{refundTime}, #{updateTime})")
    int insert(VoucherOrder voucherOrder);
}
