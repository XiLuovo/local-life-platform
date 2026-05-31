package com.sky.service;

import com.sky.entity.Blog;
import com.sky.vo.ScrollResult;
import com.sky.vo.UserProfileVO;

import java.util.List;

public interface BlogService {

    Long saveBlog(Blog blog);

    void likeBlog(Long id);

    List<Blog> queryMyBlog(Integer current);

    List<Blog> queryHotBlog(Integer current);

    Blog queryBlogById(Long id);

    List<UserProfileVO> queryBlogLikes(Long id);

    List<Blog> queryBlogByUserId(Integer current, Long userId);

    ScrollResult queryBlogOfFollow(Long max, Integer offset);
}
