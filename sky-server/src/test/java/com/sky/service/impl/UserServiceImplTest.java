package com.sky.service.impl;

import com.sky.constant.RedisConstants;
import com.sky.exception.BaseException;
import com.sky.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void sendLoginCodeUsesExplicitDevelopmentCodeWithoutReturningOrLoggingIt() {
        ReflectionTestUtils.setField(userService, "fixedLoginCode", "654321");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        userService.sendLoginCode("13800138000");

        verify(valueOperations).set(
                RedisConstants.LOGIN_CODE_KEY + "13800138000",
                "654321",
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES);
    }

    @Test
    void sendLoginCodeRejectsInvalidConfiguredDevelopmentCode() {
        ReflectionTestUtils.setField(userService, "fixedLoginCode", "1234");

        assertThatThrownBy(() -> userService.sendLoginCode("13800138000"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("6 digits");

        verify(stringRedisTemplate, never()).opsForValue();
    }
}
