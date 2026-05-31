package com.sky.service.impl;

import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.entity.Follow;
import com.sky.entity.User;
import com.sky.exception.BaseException;
import com.sky.mapper.FollowMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.FollowService;
import com.sky.vo.UserProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl implements FollowService {

    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void follow(Long followUserId, Boolean isFollow) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }

        String key = RedisConstants.FOLLOW_KEY + userId;
        if (Boolean.TRUE.equals(isFollow)) {
            Follow follow = Follow.builder()
                    .userId(userId)
                    .followUserId(followUserId)
                    .createTime(LocalDateTime.now())
                    .build();
            followMapper.insert(follow);
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            followMapper.deleteByUserAndFollowUser(userId, followUserId);
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
    }

    @Override
    public boolean isFollow(Long followUserId) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }
        return followMapper.countByUserAndFollowUser(userId, followUserId) > 0;
    }

    @Override
    public List<UserProfileVO> followCommons(Long id) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }

        loadFollowSetIfNeeded(userId);
        loadFollowSetIfNeeded(id);

        Set<String> intersect = stringRedisTemplate.opsForSet()
                .intersect(RedisConstants.FOLLOW_KEY + userId, RedisConstants.FOLLOW_KEY + id);
        if (intersect == null || intersect.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        return userMapper.listByIds(ids).stream()
                .map(this::toUserProfile)
                .collect(Collectors.toList());
    }

    private void loadFollowSetIfNeeded(Long userId) {
        String key = RedisConstants.FOLLOW_KEY + userId;
        Long size = stringRedisTemplate.opsForSet().size(key);
        if (size != null && size > 0) {
            return;
        }

        List<Long> followUserIds = followMapper.listFollowUserIdsByUserId(userId);
        if (followUserIds == null || followUserIds.isEmpty()) {
            return;
        }

        String[] values = followUserIds.stream().map(String::valueOf).toArray(String[]::new);
        stringRedisTemplate.opsForSet().add(key, values);
    }

    private UserProfileVO toUserProfile(User user) {
        return UserProfileVO.builder()
                .id(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .build();
    }
}
