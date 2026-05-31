package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.entity.Blog;
import com.sky.entity.Follow;
import com.sky.entity.User;
import com.sky.exception.BaseException;
import com.sky.mapper.BlogMapper;
import com.sky.mapper.FollowMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.BlogService;
import com.sky.vo.ScrollResult;
import com.sky.vo.UserProfileVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl implements BlogService {

    private static final int PAGE_SIZE = 10;

    @Autowired
    private BlogMapper blogMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Long saveBlog(Blog blog) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }

        LocalDateTime now = LocalDateTime.now();
        blog.setUserId(userId);
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(now);
        blog.setUpdateTime(now);
        blogMapper.insert(blog);

        List<Follow> followers = followMapper.listByFollowUserId(userId);
        for (Follow follower : followers) {
            stringRedisTemplate.opsForZSet().add(
                    RedisConstants.FEED_KEY + follower.getUserId(),
                    blog.getId().toString(),
                    System.currentTimeMillis()
            );
        }
        return blog.getId();
    }

    @Override
    public void likeBlog(Long id) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }

        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            if (blogMapper.updateLiked(id, 1) > 0) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            if (blogMapper.updateLiked(id, -1) > 0) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
    }

    @Override
    public List<Blog> queryMyBlog(Integer current) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }
        return queryBlogByUserId(current, userId);
    }

    @Override
    public List<Blog> queryHotBlog(Integer current) {
        PageHelper.startPage(normalizePage(current), PAGE_SIZE);
        Page<Blog> page = (Page<Blog>) blogMapper.listHot();
        List<Blog> records = page.getResult();
        records.forEach(this::enrichBlog);
        return records;
    }

    @Override
    public Blog queryBlogById(Long id) {
        Blog blog = blogMapper.getById(id);
        if (blog == null) {
            throw new BaseException("Blog not found");
        }
        enrichBlog(blog);
        return blog;
    }

    @Override
    public List<UserProfileVO> queryBlogLikes(Long id) {
        Set<String> topUsers = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (topUsers == null || topUsers.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = topUsers.stream().map(Long::valueOf).collect(Collectors.toList());
        Map<Long, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            orderMap.put(ids.get(i), i);
        }

        List<User> users = userMapper.listByIds(ids);
        users.sort(Comparator.comparingInt(user -> orderMap.getOrDefault(user.getId(), Integer.MAX_VALUE)));
        return users.stream().map(this::toUserProfile).collect(Collectors.toList());
    }

    @Override
    public List<Blog> queryBlogByUserId(Integer current, Long userId) {
        PageHelper.startPage(normalizePage(current), PAGE_SIZE);
        Page<Blog> page = (Page<Blog>) blogMapper.listByUserId(userId);
        List<Blog> records = page.getResult();
        records.forEach(this::enrichBlog);
        return records;
    }

    @Override
    public ScrollResult queryBlogOfFollow(Long max, Integer offset) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BaseException("User not logged in");
        }

        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(
                        RedisConstants.FEED_KEY + userId,
                        0,
                        max,
                        offset == null ? 0 : offset,
                        2
                );
        if (tuples == null || tuples.isEmpty()) {
            return ScrollResult.builder()
                    .list(Collections.emptyList())
                    .minTime(0L)
                    .offset(0)
                    .build();
        }

        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0L;
        int nextOffset = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                nextOffset++;
            } else {
                minTime = time;
                nextOffset = 1;
            }
        }

        List<Blog> blogs = listBlogsByIdsPreserveOrder(ids);
        blogs.forEach(this::enrichBlog);
        return ScrollResult.builder()
                .list(blogs)
                .minTime(minTime)
                .offset(nextOffset)
                .build();
    }

    private void enrichBlog(Blog blog) {
        User user = userMapper.getById(blog.getUserId());
        if (user != null) {
            blog.setName(user.getName());
            blog.setIcon(user.getAvatar());
        }

        Long currentUserId = BaseContext.getCurrentId();
        if (currentUserId == null) {
            blog.setIsLike(Boolean.FALSE);
            return;
        }

        Double score = stringRedisTemplate.opsForZSet()
                .score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), currentUserId.toString());
        blog.setIsLike(score != null);
    }

    private List<Blog> listBlogsByIdsPreserveOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Blog> blogs = blogMapper.listByIds(ids);
        Map<Long, Blog> blogMap = blogs.stream().collect(Collectors.toMap(Blog::getId, item -> item));
        List<Blog> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Blog blog = blogMap.get(id);
            if (blog != null) {
                ordered.add(blog);
            }
        }
        return ordered;
    }

    private UserProfileVO toUserProfile(User user) {
        return UserProfileVO.builder()
                .id(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .build();
    }

    private int normalizePage(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }
}
