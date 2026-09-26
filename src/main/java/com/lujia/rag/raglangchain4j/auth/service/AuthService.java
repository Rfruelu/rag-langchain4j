package com.lujia.rag.raglangchain4j.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lujia.rag.raglangchain4j.auth.entity.UserInfo;
import com.lujia.rag.raglangchain4j.auth.mapper.UserInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户认证服务
 */
@Slf4j
@Service
public class AuthService extends ServiceImpl<UserInfoMapper, UserInfo> {

    /**
     * 用户登录
     *
     * @param phone    手机号
     * @param password 密码（明文）
     * @return 登录用户信息（不含密码）
     */
    public UserInfo login(String phone, String password) {
        // 查询用户
        LambdaQueryWrapper<UserInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserInfo::getPhone, phone);
        UserInfo user = getOne(wrapper);

        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 校验状态
        if ("FROZEN".equals(user.getStatus())) {
            throw new RuntimeException("账号已被冻结");
        }

        // 校验密码（BCrypt 哈希比对）
        if (!BCrypt.checkpw(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // Sa-Token 登录，使用用户ID作为登录ID
        StpUtil.login(user.getId());

        // 保存用户信息到Session
        StpUtil.getSession().set("userInfo", user);

        log.info("用户登录成功: phone={}, userId={}", phone, user.getId());

        // 清除密码后返回
        user.setPassword(null);
        return user;
    }


    /**
     * 访客登录（无需密码，自动生成访客 token）
     * <p>如果数据库中不存在访客账号则自动创建，然后登录并返回 token</p>
     *
     * @return 访客用户信息（不含密码）
     */
    public UserInfo visitorLogin() {
        String visitorPhone = "visitor_000";

        // 查询是否已有访客账号
        LambdaQueryWrapper<UserInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserInfo::getPhone, visitorPhone);
        UserInfo visitor = getOne(wrapper);

        if (visitor == null) {
            // 自动创建访客账号
            visitor = new UserInfo();
            visitor.setPhone(visitorPhone);
            visitor.setPassword(cn.hutool.crypto.digest.BCrypt.hashpw("visitor_no_password"));
            visitor.setName("\u8bbf\u5ba2");
            visitor.setNickname("\u8bbf\u5ba2\u7528\u6237");
            visitor.setUserType("VISITOR");
            visitor.setStatus("ACTIVE");
            save(visitor);
            log.info("\u81ea\u52a8\u521b\u5efa\u8bbf\u5ba2\u8d26\u53f7");
        }

        // \u6821\u9a8c\u72b6\u6001
        if ("FROZEN".equals(visitor.getStatus())) {
            throw new RuntimeException("\u8bbf\u5ba2\u8d26\u53f7\u5df2\u88ab\u51bb\u7ed3");
        }

        // Sa-Token \u767b\u5f55
        StpUtil.login(visitor.getId());
        StpUtil.getSession().set("userInfo", visitor);

        log.info("\u8bbf\u5ba2\u767b\u5f55\u6210\u529f: userId={}", visitor.getId());

        visitor.setPassword(null);
        return visitor;
    }

    /**
     * 用户退出
     */
    public void logout() {
        long userId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        log.info("用户退出登录: userId={}", userId);
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户
     */
    public UserInfo getCurrentUser() {
        long userId = StpUtil.getLoginIdAsLong();
        UserInfo user = getById(userId);
        if (user != null) {
            user.setPassword(null);
        }
        return user;
    }

    /**
     * 修改密码
     *
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     */
    public void changePassword(String oldPassword, String newPassword) {
        long userId = StpUtil.getLoginIdAsLong();
        UserInfo user = getById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 验证旧密码
        if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
            throw new RuntimeException("旧密码错误");
        }

        // 更新密码（BCrypt 加密）
        user.setPassword(BCrypt.hashpw(newPassword));
        updateById(user);
        log.info("用户修改密码成功: userId={}", userId);
    }

    /**
     * 密码加密（供外部调用，如注册用户时加密密码）
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希后的密码
     */
    public static String encodePassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword);
    }

    /**
     * 重置密码（临时方法，用于更新数据库中的明文密码为 BCrypt 哈希）
     *
     * @param phone       手机号
     * @param newPassword 新密码（明文）
     */
    public void resetPassword(String phone, String newPassword) {
        LambdaQueryWrapper<UserInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserInfo::getPhone, phone);
        UserInfo user = getOne(wrapper);

        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 加密新密码并更新
        user.setPassword(BCrypt.hashpw(newPassword));
        updateById(user);
        log.info("密码重置成功: phone={}", phone);
    }
}
