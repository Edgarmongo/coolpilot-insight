package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.*;
import com.coolskyai.coolpilot.insight.entity.User;
import com.coolskyai.coolpilot.insight.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterRequest req) {
        userService.register(req.getPhone(), req.getPassword(), req.getConfirmPassword());
        return Result.ok("注册成功");
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req) {
        return Result.ok(userService.login(req.getPhone(), req.getPassword()));
    }

    @RequireRole
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        User u = userService.getCurrentUser();
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        safe.put("id", u.getId());
        safe.put("userId", u.getId());
        safe.put("username", u.getUsername());
        // 手机号脱敏，且绝不返回 passwordHash
        String phone = u.getPhone();
        safe.put("phone", phone != null && phone.length() == 11
                ? phone.substring(0, 3) + "****" + phone.substring(7) : phone);
        safe.put("role", u.getRole());
        safe.put("status", u.getStatus());
        safe.put("mustChangePwd", u.getMustChangePwd());
        safe.put("lastLoginAt", u.getLastLoginAt());
        safe.put("createdAt", u.getCreatedAt());
        return Result.ok(safe);
    }

    @RequireRole
    @PutMapping("/password")
    public Result<String> updatePassword(@RequestBody Map<String, String> body) {
        userService.updatePassword(body.get("oldPassword"), body.get("newPassword"));
        return Result.ok("密码修改成功");
    }

    @RequireRole
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        userService.logout(token);
        return Result.ok("已退出");
    }
}
