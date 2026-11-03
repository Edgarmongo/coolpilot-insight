package com.coolskyai.coolpilot.insight.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.entity.User;
import com.coolskyai.coolpilot.insight.mapper.UserMapper;
import com.coolskyai.coolpilot.insight.utils.TokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TokenStore tokenStore;

    @RequireRole(role = 1)
    @GetMapping("/users")
    public Result<Page<User>> listUsers(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(User::getId);
        Page<User> userPage = userMapper.selectPage(new Page<>(page, size), wrapper);
        userPage.getRecords().forEach(u -> {
            String phone = u.getPhone();
            if (phone != null && phone.length() == 11) {
                u.setPhone(phone.substring(0, 3) + "****" + phone.substring(7));
            }
            // 密码哈希永不外泄
            u.setPasswordHash(null);
        });
        return Result.ok(userPage);
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
