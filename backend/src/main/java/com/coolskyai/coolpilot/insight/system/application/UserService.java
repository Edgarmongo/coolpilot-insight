package com.coolskyai.coolpilot.insight.system.application;

import com.coolskyai.coolpilot.insight.system.dto.LoginResponse;
import com.coolskyai.coolpilot.insight.system.domain.User;

public interface UserService {
    void register(String phone, String password, String confirmPassword);
    LoginResponse login(String phone, String password);
    User getCurrentUser();
    void updatePassword(String oldPassword, String newPassword);
    void logout(String token);
}
