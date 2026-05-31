package com.sky.controller.user;

import com.sky.constant.JwtClaimsConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.properties.JwtProperties;
import com.sky.result.Result;
import com.sky.service.UserService;
import com.sky.utils.JwtUtil;
import com.sky.vo.UserLoginVO;
import com.sky.vo.UserProfileVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user/user")
@Api(tags = "User APIs")
@Slf4j
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private JwtProperties jwtProperties;

    @PostMapping("/code")
    @ApiOperation("Send login code")
    public Result<Void> sendCode(@RequestParam String phone) {
        log.info("Send login code for phone: {}", phone);
        userService.sendLoginCode(phone);
        return Result.success();
    }

    @PostMapping("/login")
    @ApiOperation("Login with phone verification code")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
        log.info("Phone login attempt for: {}", userLoginDTO.getPhone());

        User user = userService.login(userLoginDTO);

        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);

        UserLoginVO userLoginVO = UserLoginVO.builder()
                .id(user.getId())
                .phone(user.getPhone())
                .name(user.getName())
                .avatar(user.getAvatar())
                .token(token)
                .build();
        return Result.success(userLoginVO);
    }

    @GetMapping("/me")
    @ApiOperation("Query current user")
    public Result<UserProfileVO> me() {
        User user = userService.getById(com.sky.context.BaseContext.getCurrentId());
        return Result.success(UserProfileVO.builder()
                .id(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .build());
    }

    @GetMapping("/{id}")
    @ApiOperation("Query user by id")
    public Result<UserProfileVO> queryUserById(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("User not found");
        }
        return Result.success(UserProfileVO.builder()
                .id(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .build());
    }

    @PostMapping("/sign")
    @ApiOperation("Daily sign-in")
    public Result<Void> sign() {
        userService.sign();
        return Result.success();
    }

    @GetMapping("/sign/count")
    @ApiOperation("Consecutive sign-in count")
    public Result<Integer> signCount() {
        return Result.success(userService.signCount());
    }
}
