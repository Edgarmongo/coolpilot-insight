package com.coolskyai.coolpilot.insight.service.impl;

import com.coolskyai.coolpilot.insight.dto.LoginResponse;
import com.coolskyai.coolpilot.insight.entity.User;
import com.coolskyai.coolpilot.insight.interceptor.RequestContext;
import com.coolskyai.coolpilot.insight.mapper.UserMapper;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import com.coolskyai.coolpilot.insight.service.UserService;
import com.coolskyai.coolpilot.insight.utils.JwtUtil;
import com.coolskyai.coolpilot.insight.utils.TokenStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {

    private static final String SENTINEL = "!SENTINEL_NO_LOGIN!";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private GovernanceService governance;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void register(String phone, String password, String confirmPassword) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new IllegalArgumentException("密码长度为 8-64 位");
        }
        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("两次密码输入不一致");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone)) > 0) {
            throw new IllegalStateException("该手机号已被注册");
        }

        User user = new User();
        user.setPhone(phone);
        user.setUsername(phone.substring(0, 3) + "****" + phone.substring(7));
        user.setPasswordHash(encoder.encode(password));
        user.setRole(0);
        user.setStatus(1);
        user.setMustChangePwd(0);
        userMapper.insert(user);
    }

    @Override
    public LoginResponse login(String phone, String password) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone).isNull(User::getDeletedAt).last("LIMIT 1"));
        if (user == null) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        if (SENTINEL.equals(user.getPasswordHash())) {
            throw new IllegalStateException("该账号尚未初始化密码，请联系管理员");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new IllegalStateException("账号已被禁用");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("账号或密码错误");
        }

        String token = jwtUtil.createToken(user.getId(), user.getRole());
        tokenStore.put(token, user.getId(), 24 * 3600 * 1000L);

        User patch = new User();
        patch.setId(user.getId());
        patch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(patch);

        // 登录审计：只记录行为，不记录凭据
        RequestContext.setUserId(user.getId());
        RequestContext.setRole(user.getRole());
        governance.audit("LOGIN", "USER", user.getId(), null);

        String maskPhone = phone.substring(0, 3) + "****" + phone.substring(7);
        LoginResponse resp = new LoginResponse(token, user.getId(), maskPhone, user.getRole());
        resp.setMustChangePwd(user.getMustChangePwd());
        return resp;
    }

    @Override
    public User getCurrentUser() {
        Long userId = RequestContext.getUserId();
        if (userId == null) {
            throw new SecurityException("未登录");
        }
        return userMapper.selectById(userId);
    }

    @Override
    public void updatePassword(String oldPassword, String newPassword) {
        User user = getCurrentUser();
        boolean ok = user.getPasswordHash() != null
                && !SENTINEL.equals(user.getPasswordHash())
                && encoder.matches(oldPassword, user.getPasswordHash());
        if (!ok) {
            throw new IllegalArgumentException("原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 64) {
            throw new IllegalArgumentException("新密码长度为 8-64 位");
        }
        User patch = new User();
        patch.setId(user.getId());
        patch.setPasswordHash(encoder.encode(newPassword));
        patch.setMustChangePwd(0);
        userMapper.updateById(patch);
        governance.audit("CHANGE_PASSWORD", "USER", user.getId(), null);
    }

    @Override
    public void logout(String token) {
        tokenStore.remove(token);
    }
}
