package com.lujia.rag.raglangchain4j.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lujia.rag.raglangchain4j.auth.entity.UserInfo;
import com.lujia.rag.raglangchain4j.auth.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户认证接口
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 用户登录
     *
     * @param params 包含 phone（手机号）和 password（密码）
     * @return 登录结果，包含 token 和用户信息
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> params) {
        String phone = params.get("phone");
        String password = params.get("password");

        if (phone == null || phone.isBlank()) {
            throw new RuntimeException("手机号不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new RuntimeException("密码不能为空");
        }

        UserInfo user = authService.login(phone, password);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", StpUtil.getTokenValue());
        result.put("user", user);
        return result;
    }


    /**
     * 访客登录（无需密码）
     *
     * @return 访客 token 和用户信息
     */
    @PostMapping("/visitor")
    public Map<String, Object> visitorLogin() {
        UserInfo user = authService.visitorLogin();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", StpUtil.getTokenValue());
        result.put("user", user);
        return result;
    }

    /**
     * 用户退出
     *
     * @return 退出结果
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        authService.logout();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "退出成功");
        return result;
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户
     */
    @GetMapping("/current")
    public Map<String, Object> getCurrentUser() {
        UserInfo user = authService.getCurrentUser();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", user);
        return result;
    }

    /**
     * 重置密码（临时接口，用于更新数据库中的明文密码为 BCrypt 哈希）
     * 使用后可删除此接口
     *
     * @param params 包含 phone（手机号）和 newPassword（新密码）
     * @return 重置结果
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody Map<String, String> params) {
        String phone = params.get("phone");
        String newPassword = params.get("newPassword");

        if (phone == null || phone.isBlank()) {
            throw new RuntimeException("手机号不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new RuntimeException("新密码不能为空");
        }

        // 仅允许重置当前登录用户本人的密码
        UserInfo currentUser = authService.getCurrentUser();
        if (currentUser == null || !phone.equals(currentUser.getPhone())) {
            throw new RuntimeException("只能重置当前登录账号的密码");
        }

        authService.resetPassword(phone, newPassword);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "密码重置成功");
        return result;
    }
}
