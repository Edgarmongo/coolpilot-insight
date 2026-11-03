package com.coolskyai.coolpilot.insight.service;

import com.coolskyai.coolpilot.insight.dto.LoginResponse;
import com.coolskyai.coolpilot.insight.entity.User;

public interface UserService {
    void register(String phone, String password, String confirmPassword);
    LoginResponse login(String phone, String password);
    User getCurrentUser();
    void updatePassword(String oldPassword, String newPassword);
    void logout(String token);
}
