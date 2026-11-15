package com.coolskyai.coolpilot.insight.system.application;

import com.coolskyai.coolpilot.insight.common.web.PageResult;
import com.coolskyai.coolpilot.insight.system.domain.User;
import com.coolskyai.coolpilot.insight.system.dto.UserView;
import com.coolskyai.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import com.coolskyai.coolpilot.insight.utils.TokenStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户管理用例（管理员视角）。
 *
 * <p>从原 {@code AdminController} 下沉而来。Controller 之前直接注入 Mapper、
 * 在方法体里做分页拼装与字段脱敏，违反「Controller 不访问 Mapper / 不承载业务逻辑」。
 * 现在 Controller 只负责取参与返回，规则都在这一层。
 */
@Service
public class UserAdminService {

    private final UserMapper userMapper;
    private final TokenStore tokenStore;

    public UserAdminService(UserMapper userMapper, TokenStore tokenStore) {
        this.userMapper = userMapper;
        this.tokenStore = tokenStore;
    }

    /** 分页查询用户，返回已脱敏视图。 */
    public PageResult<UserView> list(int page, int size) {
        int current = Math.max(page, 1);
        int pageSize = Math.max(size, 1);
        List<User> rows = userMapper.selectPageOrderById((current - 1) * pageSize, pageSize);
        long total = userMapper.countAll();
        List<UserView> views = rows.stream().map(UserAdminService::toView).toList();
        return PageResult.of(views, total, current, pageSize);
    }

    /**
     * 删除用户。同时清理该用户的登录态，避免删号后旧 token 仍可用。
     *
     * @return 是否真的删除了记录（不存在时返回 false，由调用方决定提示语）
     */
    public boolean delete(Long userId) {
        User existing = userMapper.selectById(userId);
        if (existing == null) {
            return false;
        }
        tokenStore.removeByUserId(userId);
        userMapper.deleteById(userId);
        return true;
    }

    /** 手机号中间四位打码；非 11 位原样返回，避免越界。 */
    static UserView toView(User u) {
        UserView v = new UserView();
        v.setId(u.getId());
        v.setUsername(u.getUsername());
        v.setPhone(maskPhone(u.getPhone()));
        v.setRole(u.getRole());
        v.setStatus(u.getStatus());
        v.setMustChangePwd(u.getMustChangePwd());
        v.setLastLoginAt(u.getLastLoginAt());
        v.setCreatedAt(u.getCreatedAt());
        return v;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
