package com.user.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.dto.user.UserCreateDto;
import com.equipops.domain.vo.UserVO;
import com.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户接口：接收请求并调用 UserService。 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    // ServiceImpl 尚未编写时，先允许项目正常启动。
    private final UserService userService;

    /** 新增用户。 */
    @PostMapping
    public Result<UserVO> create(@Valid @RequestBody UserCreateDto request) {
        UserVO userVO = userService.createUser(request);
        return Result.success(userVO);
    }

    /** 分页查询用户；keyword 和 enabled 不传时不筛选。 */
    @GetMapping
    public Result<IPage<UserVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(userService.getUserPage(current, size, keyword, enabled));
    }
    @GetMapping("/id")
    public Result<UserVO> getUserByID(@RequestParam Long id) {
        UserVO userVO=userService.getUserByID(id);
        return Result.success(userVO);
    }
}
