package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    /**
     * Insert order data.
     *
     * @param orders order entity
     */
    void insert(Orders orders);

    /**
     * Query order by order number and user id.
     *
     * @param orderNumber order number
     * @param userId user id
     * @return order entity
     */
    @Select("select * from orders where number = #{orderNumber} and user_id= #{userId}")
    Orders getByNumberAndUserId(@Param("orderNumber") String orderNumber, @Param("userId") Long userId);

    /**
     * Query order by order number.
     *
     * @param orderNumber order number
     * @return order entity
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * Update order data.
     *
     * @param orders order entity
     */
    void update(Orders orders);

    /**
     * Update an order only when it is still in the expected status.
     *
     * @param orders order fields to update
     * @param expectedStatus current expected status
     * @return affected rows
     */
    int updateByIdAndStatus(@Param("order") Orders orders, @Param("expectedStatus") Integer expectedStatus);

    /**
     * Query orders by conditions with paging.
     *
     * @param ordersPageQueryDTO query parameters
     * @return paged orders
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * Query order by id.
     *
     * @param id order id
     * @return order entity
     */
    @Select("select * from orders where id=#{id}")
    Orders getById(Long id);

    /**
     * Query a user order by id. User-facing operations must use this method
     * instead of trusting an order id supplied by the client.
     */
    @Select("select * from orders where id = #{id} and user_id = #{userId}")
    Orders getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Count orders by status.
     *
     * @param status status
     * @return count
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);

    /**
     * Query orders by status and order time less than the specified time.
     *
     * @param status status
     * @param orderTime upper bound of order time
     * @return matching orders
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(Integer status, LocalDateTime orderTime);

    /**
     * Sum order amount by dynamic conditions.
     *
     * @param map query conditions
     * @return total amount
     */
    Double sumByMap(Map map);

    /**
     * Count orders by dynamic conditions.
     *
     * @param map query conditions
     * @return count
     */
    Integer countByMap(Map map);

    /**
     * Query sales top 10 in the given period.
     *
     * @param begin start time
     * @param end end time
     * @return top selling goods
     */
    List<GoodsSalesDTO> getSalesTop10(LocalDateTime begin, LocalDateTime end);
}
