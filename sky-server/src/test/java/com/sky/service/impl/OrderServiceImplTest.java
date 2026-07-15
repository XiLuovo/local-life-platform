package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.entity.User;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderSubmitVO;
import com.sky.websocket.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final Long CURRENT_USER_ID = 100L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderDetailMapper orderDetailMapper;
    @Mock
    private AddressBookMapper addressBookMapper;
    @Mock
    private ShoppingCartMapper shoppingCartMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private WeChatPayUtil weChatPayUtil;
    @Mock
    private WebSocketServer webSocketServer;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(CURRENT_USER_ID);
        ReflectionTestUtils.setField(orderService, "packFeePerItem", 2);
        ReflectionTestUtils.setField(orderService, "mockPayEnabled", false);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void submitOrderRejectsAddressNotOwnedByCurrentUser() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(10L);
        when(addressBookMapper.getByIdAndUserId(10L, CURRENT_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.submitOrder(dto))
                .isInstanceOf(AddressBookBusinessException.class);

        verify(orderMapper, never()).insert(any());
        verify(shoppingCartMapper, never()).deleteByUserId(anyLong());
    }

    @Test
    void submitOrderCalculatesAmountOnServerAndIgnoresClientValues() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setAddressBookId(10L);
        dto.setPayMethod(1);
        dto.setAmount(new BigDecimal("0.01"));
        dto.setPackAmount(999);

        AddressBook address = AddressBook.builder()
                .id(10L)
                .userId(CURRENT_USER_ID)
                .detail("测试地址")
                .phone("13800000000")
                .consignee("测试用户")
                .build();
        when(addressBookMapper.getByIdAndUserId(10L, CURRENT_USER_ID)).thenReturn(address);

        ShoppingCart first = ShoppingCart.builder()
                .dishId(1L)
                .name("商品一")
                .amount(new BigDecimal("10.50"))
                .number(2)
                .build();
        ShoppingCart second = ShoppingCart.builder()
                .setmealId(2L)
                .name("商品二")
                .amount(new BigDecimal("5.20"))
                .number(1)
                .build();
        when(shoppingCartMapper.list(any(ShoppingCart.class))).thenReturn(Arrays.asList(first, second));
        doAnswer(invocation -> {
            Orders orders = invocation.getArgument(0);
            orders.setId(88L);
            return null;
        }).when(orderMapper).insert(any(Orders.class));

        OrderSubmitVO result = orderService.submitOrder(dto);

        ArgumentCaptor<Orders> ordersCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(orderMapper).insert(ordersCaptor.capture());
        Orders savedOrder = ordersCaptor.getValue();
        assertThat(savedOrder.getAmount()).isEqualByComparingTo("32.20");
        assertThat(savedOrder.getPackAmount()).isEqualTo(6);
        assertThat(savedOrder.getUserId()).isEqualTo(CURRENT_USER_ID);
        assertThat(result.getOrderAmount()).isEqualByComparingTo("32.20");
        verify(shoppingCartMapper).deleteByUserId(CURRENT_USER_ID);
    }

    @Test
    void detailsRejectsAnotherUsersOrder() {
        when(orderMapper.getByIdAndUserId(20L, CURRENT_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.details(20L))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderDetailMapper, never()).getByOrderId(anyLong());
    }

    @Test
    void cancelRejectsAnotherUsersOrder() {
        when(orderMapper.getByIdAndUserId(20L, CURRENT_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.userCancelById(20L))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderMapper, never()).updateByIdAndStatus(any(), any());
    }

    @Test
    void repetitionRejectsAnotherUsersOrder() {
        when(orderMapper.getByIdAndUserId(20L, CURRENT_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.repetition(20L))
                .isInstanceOf(OrderBusinessException.class);

        verify(orderDetailMapper, never()).getByOrderId(anyLong());
        verify(shoppingCartMapper, never()).insertBatch(any());
    }

    @Test
    void reminderRejectsAnotherUsersOrder() {
        when(orderMapper.getByIdAndUserId(20L, CURRENT_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.reminder(20L))
                .isInstanceOf(OrderBusinessException.class);

        verify(webSocketServer, never()).sendToAllClient(any());
    }

    @Test
    void paymentRejectsOrderNotOwnedByCurrentUser() throws Exception {
        OrdersPaymentDTO dto = new OrdersPaymentDTO();
        dto.setOrderNumber("ORDER-1");
        dto.setPayMethod(1);
        when(userMapper.getById(CURRENT_USER_ID)).thenReturn(User.builder().id(CURRENT_USER_ID).openid("openid").build());
        when(orderMapper.getByNumberAndUserId("ORDER-1", CURRENT_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.payment(dto))
                .isInstanceOf(OrderBusinessException.class);

        verify(weChatPayUtil, never()).pay(any(), any(), any(), any());
    }

    @Test
    void paymentUsesAmountStoredInCurrentUsersOrder() throws Exception {
        OrdersPaymentDTO dto = new OrdersPaymentDTO();
        dto.setOrderNumber("ORDER-2");
        dto.setPayMethod(1);

        User user = User.builder().id(CURRENT_USER_ID).openid("openid").build();
        Orders order = Orders.builder()
                .id(30L)
                .number("ORDER-2")
                .userId(CURRENT_USER_ID)
                .status(Orders.PENDING_PAYMENT)
                .payStatus(Orders.UN_PAID)
                .payMethod(1)
                .amount(new BigDecimal("42.50"))
                .build();
        when(userMapper.getById(CURRENT_USER_ID)).thenReturn(user);
        when(orderMapper.getByNumberAndUserId("ORDER-2", CURRENT_USER_ID)).thenReturn(order);
        JSONObject paymentResponse = new JSONObject();
        paymentResponse.put("package", "prepay_id=test");
        when(weChatPayUtil.pay(
                eq("ORDER-2"),
                argThat(amount -> amount.compareTo(new BigDecimal("42.50")) == 0),
                eq("Order payment"),
                eq("openid")))
                .thenReturn(paymentResponse);

        orderService.payment(dto);

        verify(weChatPayUtil).pay(
                eq("ORDER-2"),
                argThat(amount -> amount.compareTo(new BigDecimal("42.50")) == 0),
                eq("Order payment"),
                eq("openid"));
    }
}
