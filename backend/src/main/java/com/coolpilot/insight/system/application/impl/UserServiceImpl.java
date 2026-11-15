package com.coolpilot.insight.system.application.impl;

import com.coolpilot.insight.system.dto.LoginResponse;
import com.coolpilot.insight.system.domain.User;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.common.security.JwtUtil;
import com.coolpilot.insight.utils.TokenStore;
import com.coolpilot.insight.system.application.AuditService;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
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
    private AuditService auditService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void register(String phone, String password, String confirmPassword) {
        if (phone == null || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "密码长度为 8-64 位");
        }
        if (!password.equals(confirmPassword)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "两次密码输入不一致");
        }
        if (userMapper.countByPhone(phone) > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "该手机号已被注册");
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
        User user = userMapper.selectByPhone(phone);
        if (user == null) {
            throw new BizException(ErrorCode.CREDENTIALS_INVALID, "账号或密码错误");
        }
        if (SENTINEL.equals(user.getPasswordHash())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该账号尚未初始化密码，请联系管理员");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.CREDENTIALS_INVALID, "账号或密码错误");
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
        auditService.audit("LOGIN", "USER", user.getId(), null);

        String maskPhone = phone.substring(0, 3) + "****" + phone.substring(7);
        LoginResponse resp = new LoginResponse(token, user.getId(), maskPhone, user.getRole());
        resp.setMustChangePwd(user.getMustChangePwd());
        return resp;
    }

    @Override
    public User getCurrentUser() {
        Long userId = RequestContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
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
            throw new BizException(ErrorCode.CREDENTIALS_INVALID, "原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码长度为 8-64 位");
        }
        User patch = new User();
        patch.setId(user.getId());
        patch.setPasswordHash(encoder.encode(newPassword));
        patch.setMustChangePwd(0);
        userMapper.updateById(patch);
        auditService.audit("CHANGE_PASSWORD", "USER", user.getId(), null);
    }

    @Override
    public void logout(String token) {
        tokenStore.remove(token);
    }
}
