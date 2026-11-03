package com.coolskyai.coolpilot.insight.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.dto.LoginResponse;
import com.coolskyai.coolpilot.insight.entity.User;
import com.coolskyai.coolpilot.insight.interceptor.RequestContext;
import com.coolskyai.coolpilot.insight.mapper.UserMapper;
import com.coolskyai.coolpilot.insight.service.UserService;
import com.coolskyai.coolpilot.insight.utils.JwtUtil;
import com.coolskyai.coolpilot.insight.utils.TokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void register(String phone, String password, String confirmPassword) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new RuntimeException("手机号格式不正确");
        }
        if (password == null || password.length() < 6 || password.length() > 20) {
            throw new RuntimeException("密码长度为6-20位");
        }
        if (!password.equals(confirmPassword)) {
            throw new RuntimeException("两次密码输入不一致");
        }

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, phone);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("该手机号已被注册");
        }

        User user = new User();
        user.setPhone(phone);
        user.setUsername(phone.substring(0, 3) + "****" + phone.substring(7));
        user.setPassword(encoder.encode(password));
        user.setRole(0);
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
    }

    @Override
    public LoginResponse login(String phone, String password) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, phone);
        User user = userMapper.selectOne(wrapper);
        if (user == null) {
            throw new RuntimeException("账号不存在");
        }

        // 兼容老密码（MD5）和新密码（BCrypt）
        boolean match;
        if (user.getPassword().startsWith("$2a$") || user.getPassword().startsWith("$2b$")) {
            match = encoder.matches(password, user.getPassword());
        } else {
            match = DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8)).equals(user.getPassword());
        }

        if (!match) {
            throw new RuntimeException("密码错误");
        }

        String token = jwtUtil.createToken(user.getId(), user.getRole());
        tokenStore.put(token, user.getId(), 24 * 3600 * 1000L);

        String maskPhone = phone.substring(0, 3) + "****" + phone.substring(7);
        return new LoginResponse(token, user.getId(), maskPhone, user.getRole());
    }

    @Override
    public User getCurrentUser() {
        Long userId = RequestContext.getUserId();
        if (userId == null) {
            throw new RuntimeException("未登录");
        }
        return userMapper.selectById(userId);
    }

    @Override
    public void updatePassword(String oldPassword, String newPassword) {
        User user = getCurrentUser();
        // 兼容老密码验证
        boolean match;
        if (user.getPassword().startsWith("$2a$") || user.getPassword().startsWith("$2b$")) {
            match = encoder.matches(oldPassword, user.getPassword());
        } else {
            match = DigestUtils.md5DigestAsHex(oldPassword.getBytes(StandardCharsets.UTF_8)).equals(user.getPassword());
        }
        if (!match) {
            throw new RuntimeException("原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 20) {
            throw new RuntimeException("新密码长度为6-20位");
        }
        // 新密码统一用 BCrypt
        user.setPassword(encoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @Override
    public void logout(String token) {
        tokenStore.remove(token);
    }
}
