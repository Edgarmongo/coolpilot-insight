package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.entity.User;
import com.coolskyai.coolpilot.insight.mapper.UserMapper;
import com.coolskyai.coolpilot.insight.utils.TokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TokenStore tokenStore;

    @RequireRole(role = 1)
    @GetMapping("/users")
    public Result<Map<String, Object>> listUsers(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(page, 1);
        int s = Math.max(size, 1);
        List<User> records = userMapper.selectPageOrderById((p - 1) * s, s);
        long total = userMapper.countAll();
        records.forEach(u -> {
            String phone = u.getPhone();
            if (phone != null && phone.length() == 11) {
                u.setPhone(phone.substring(0, 3) + "****" + phone.substring(7));
            }
            // 密码哈希永不外泄
            u.setPasswordHash(null);
        });
        // 保持与原 MyBatis-Plus Page 一致的字段结构，前端无需改动
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        out.put("size", s);
        out.put("current", p);
        out.put("pages", (total + s - 1) / s);
        return Result.ok(out);
    }

    @RequireRole(role = 1)
    @DeleteMapping("/user/{id}")
    public Result<String> deleteUser(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user != null) {
            tokenStore.removeByUserId(id);
            userMapper.deleteById(id);
        }
        return Result.ok("删除成功");
    }
}
