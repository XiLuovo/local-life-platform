package com.sky.service;

import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;

import java.util.List;

public interface UserService {

    void sendLoginCode(String phone);

    User login(UserLoginDTO userLoginDTO);

    User getById(Long userId);

    List<User> listByIds(List<Long> ids);

    void sign();

    int signCount();
}
