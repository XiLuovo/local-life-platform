package com.sky.service.impl;

import com.sky.constant.RedisConstants;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.BaseException;
import com.sky.mapper.UserMapper;
import com.sky.service.UserService;
import com.sky.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private static final String USER_NAME_PREFIX = "user_";
    private static final DateTimeFormatter SIGN_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern(":yyyyMM");

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${sky.auth.fixed-login-code:}")
    private String fixedLoginCode;

    @Override
    public void sendLoginCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BaseException("Invalid phone number");
        }

        if (StringUtils.hasText(fixedLoginCode) && RegexUtils.isCodeInvalid(fixedLoginCode)) {
            throw new BaseException("Fixed login code must be exactly 6 digits");
        }
        String code = StringUtils.hasText(fixedLoginCode)
                ? fixedLoginCode
                : String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES
        );
        log.info("Login verification code generated");
    }

    @Override
    public User login(UserLoginDTO userLoginDTO) {
        if (userLoginDTO == null) {
            throw new BaseException("Login payload cannot be null");
        }

        String phone = userLoginDTO.getPhone();
        String code = userLoginDTO.getCode();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BaseException("Invalid phone number");
        }
        if (RegexUtils.isCodeInvalid(code)) {
            throw new BaseException("Invalid verification code");
        }

        String redisKey = RedisConstants.LOGIN_CODE_KEY + phone;
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (!StringUtils.hasText(cachedCode) || !cachedCode.equals(code)) {
            throw new BaseException("Verification code is incorrect or expired");
        }

        User user = userMapper.getByPhone(phone);
        if (user == null) {
            user = registerPhoneUser(phone);
        }

        stringRedisTemplate.delete(redisKey);
        return user;
    }

    @Override
    public User getById(Long userId) {
        return userMapper.getById(userId);
    }

    @Override
    public List<User> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return userMapper.listByIds(ids);
    }

    @Override
    public void sign() {
        User currentUser = getCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        String key = RedisConstants.USER_SIGN_KEY + currentUser.getId() + now.format(SIGN_SUFFIX_FORMATTER);
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
    }

    @Override
    public int signCount() {
        User currentUser = getCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        String key = RedisConstants.USER_SIGN_KEY + currentUser.getId() + now.format(SIGN_SUFFIX_FORMATTER);
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return 0;
        }

        long num = result.get(0);
        int count = 0;
        while ((num & 1) == 1) {
            count++;
            num >>>= 1;
        }
        return count;
    }

    private User registerPhoneUser(String phone) {
        User user = User.builder()
                .phone(phone)
                .name(USER_NAME_PREFIX + phone.substring(phone.length() - 4))
                .avatar("")
                .createTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        return user;
    }

    private User getCurrentUser() {
        User user = userMapper.getById(com.sky.context.BaseContext.getCurrentId());
        if (user == null) {
            throw new BaseException("User not logged in");
        }
        return user;
    }
}
