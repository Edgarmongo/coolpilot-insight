package com.coolskyai.coolpilot.insight.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private String token;
    private Long userId;
    private String phone;
    private Integer role;

    public LoginResponse(String token, Long userId, String phone, Integer role) {
        this.token = token;
        this.userId = userId;
        this.phone = phone;
        this.role = role;
    }
}
