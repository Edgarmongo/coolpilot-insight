package com.coolskyai.coolpilot.insight.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String phone;
    private String password;
    private String confirmPassword;
}
