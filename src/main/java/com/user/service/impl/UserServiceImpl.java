package com.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.user.UserCreateDto;
import com.equipops.domain.entity.UserEntity;
import com.equipops.domain.vo.UserVO;
import com.user.service.UserService;
import com.workorder.mapper.UserMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO createUser(UserCreateDto request) {
        //接收 UserCreateDto,校验账号、密码、姓名等必填项
        /** 1接收 UserCreateDto↓2
         * 2校验账号、密码、姓名等必填项↓
         * 3检查账号是否重复↓创建 UserEntity，设置允许写入的字段
         * 4将密码转换为安全哈希，保存到 passwordHash
         * 5设置 enabled = true
         * 6保存用户，检查保存结果
         * 7转换为 UserVO 返回*/
        if(request.getAccount() == null || request.getAccount().equals("")) {
            throw new IllegalArgumentException("账户填写有误，请重新填写！");
        }
        // 查重，使用MyBatis-Plus Wrapper 语法）
        /*** SELECT COUNT(*) FROM users WHERE account = '前端传过来的账号';*/
        Long count =userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getAccount, request.getAccount())
        );
        // 3. 如果已经存在，抛异常
        if (count > 0) {
            throw new IllegalArgumentException("该账号已存在，请更换其他账号！");
        }
        UserEntity userEntity = new UserEntity();
        BeanUtils.copyProperties(request, userEntity);
        //4填写用户密码,把明文密码加密成哈希值再保存
        String encryptedPwd = passwordEncoder.encode(request.getPassword());
        userEntity.setPasswordHash(encryptedPwd);
        //5.设置 enabled = true,启用账号
        // 新建用户默认启用。
        userEntity.setEnabled(true);
        // 保存入库并检查结果。
        if(userMapper.insert(userEntity) != 1) {
            throw new IllegalStateException("新增用户失败");
        }

        //7封装返回的结果
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(userEntity, userVO);
        //返回给前端
        return userVO;
    }

    @Override
    public IPage<UserVO> getUserPage(long current, long size, String keyword, Boolean enabled) {
        // 校验页码和每页数量，避免查询范围过大。
        if (current < 1) {
            current = 1;
        }
        if (size < 1) {
            size = 1;
        } else if (size > 100) {
            size = 100;
        }

        // 创建分页参数和查询条件。
        Page<UserEntity> page = new Page<>(current, size);
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();

        // 传了关键字，就按用户显示名称模糊查询。
        wrapper.like(StringUtils.hasText(keyword), UserEntity::getDisplayName, keyword);
        // 传了启用状态，就筛选启用或停用的用户。
        wrapper.eq(enabled != null, UserEntity::getEnabled, enabled);
        // 最新创建的用户排在前面。
        wrapper.orderByDesc(UserEntity::getId);

        // 执行分页查询，并将结果转换为不含密码的 UserVO。
        IPage<UserEntity> entityPage = userMapper.selectPage(page, wrapper);
        return entityPage.convert(this::toVO);
    }

    @Override
    public UserVO getUserByID(Long id) {
        //1.接受前端用户ID
        // 1. 检查请求参数。
        if (id==null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.valueOf(400), "用户ID有误");//BAD_REQUEST,400
        }
        //2.通过 UserMapper 查询 users 表
       UserEntity userVO1 =userMapper.selectById(id);
        //3.用户不存在 → 返回 404
        if (userVO1==null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");//NOT_FOUND,404
        }else {
            //用户存在 → 转换为 UserVO 返回
            UserVO userVO2 = new UserVO();
            BeanUtils.copyProperties(userVO1,userVO2);
            //返回前端用户数据
            return userVO2;
        }
    }

    /** 将数据库用户转换为返回给前端的用户信息。 */
    private UserVO toVO(UserEntity userEntity) {
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(userEntity, userVO);
        return userVO;
    }
}
