package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.FollowService;
import com.sky.vo.UserProfileVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/follow")
@Api(tags = "Follow social APIs")
public class FollowController {

    @Autowired
    private FollowService followService;

    @PutMapping("/{id}/{isFollow}")
    @ApiOperation("Follow or unfollow a user")
    public Result<Void> follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        followService.follow(followUserId, isFollow);
        return Result.success();
    }

    @GetMapping("/or/not/{id}")
    @ApiOperation("Check if followed")
    public Result<Boolean> isFollow(@PathVariable("id") Long followUserId) {
        return Result.success(followService.isFollow(followUserId));
    }

    @GetMapping("/common/{id}")
    @ApiOperation("Query common follows")
    public Result<List<UserProfileVO>> followCommons(@PathVariable("id") Long id) {
        return Result.success(followService.followCommons(id));
    }
}
