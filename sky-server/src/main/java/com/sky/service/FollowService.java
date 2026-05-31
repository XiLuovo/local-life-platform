package com.sky.service;

import com.sky.vo.UserProfileVO;

import java.util.List;

public interface FollowService {

    void follow(Long followUserId, Boolean isFollow);

    boolean isFollow(Long followUserId);

    List<UserProfileVO> followCommons(Long id);
}
