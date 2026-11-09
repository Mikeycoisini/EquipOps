package com.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.user.UserCreateDto;
import com.equipops.domain.vo.UserVO;

/** 用户业务服务契约，具体业务由 ServiceImpl 实现。 */
public interface UserService {

    /** 新增用户并返回不包含密码信息的用户数据。 */
    UserVO createUser(UserCreateDto request);

    /** 按页查询用户，可按关键字和启用状态筛选。 */
    IPage<UserVO> getUserPage(long current, long size, String keyword, Boolean enabled);

    UserVO getUserByID(Long id);
}
