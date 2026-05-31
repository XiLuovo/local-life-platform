package com.sky.controller.user;

import com.sky.entity.Blog;
import com.sky.result.Result;
import com.sky.service.BlogService;
import com.sky.vo.ScrollResult;
import com.sky.vo.UserProfileVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/blog")
@Api(tags = "Blog social APIs")
public class BlogController {

    @Autowired
    private BlogService blogService;

    @PostMapping
    @ApiOperation("Publish a blog")
    public Result<Long> saveBlog(@RequestBody Blog blog) {
        return Result.success(blogService.saveBlog(blog));
    }

    @PutMapping("/like/{id}")
    @ApiOperation("Like or unlike a blog")
    public Result<Void> likeBlog(@PathVariable("id") Long id) {
        blogService.likeBlog(id);
        return Result.success();
    }

    @GetMapping("/of/me")
    @ApiOperation("Query current user's blogs")
    public Result<List<Blog>> queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return Result.success(blogService.queryMyBlog(current));
    }

    @GetMapping("/hot")
    @ApiOperation("Query hot blogs")
    public Result<List<Blog>> queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return Result.success(blogService.queryHotBlog(current));
    }

    @GetMapping("/{id}")
    @ApiOperation("Query blog by id")
    public Result<Blog> queryBlogById(@PathVariable("id") Long id) {
        return Result.success(blogService.queryBlogById(id));
    }

    @GetMapping("/likes/{id}")
    @ApiOperation("Query blog likes")
    public Result<List<UserProfileVO>> queryBlogLikes(@PathVariable("id") Long id) {
        return Result.success(blogService.queryBlogLikes(id));
    }

    @GetMapping("/of/user")
    @ApiOperation("Query blogs by user id")
    public Result<List<Blog>> queryBlogByUserId(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                                @RequestParam("id") Long id) {
        return Result.success(blogService.queryBlogByUserId(current, id));
    }

    @GetMapping("/of/follow")
    @ApiOperation("Query followed users feed")
    public Result<ScrollResult> queryBlogOfFollow(@RequestParam("lastId") Long max,
                                                  @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return Result.success(blogService.queryBlogOfFollow(max, offset));
    }
}
