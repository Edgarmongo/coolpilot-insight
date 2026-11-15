package com.coolpilot.insight.system;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.web.PageResult;
import com.coolpilot.insight.system.application.UserAdminService;
import com.coolpilot.insight.system.dto.UserView;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import com.coolpilot.insight.system.application.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户管理用例（R2 从 AdminController 下沉出来的业务逻辑）。
 *
 * <p>这组用例的意义：重构把分页与脱敏从 Controller 移到 application 层，
 * 必须用测试证明行为没变——否则"移动"和"改坏"无法区分。
 */
class UserAdminServiceTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "Passw0rd!1234";

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private String registerOne() {
        String phone = "138" + String.format("%08d", 20_000_000 + SEQ.getAndIncrement());
        userService.register(phone, PASSWORD, PASSWORD);
        return phone;
    }

    @Test
    @DisplayName("分页返回统一结构，且手机号已脱敏")
    void listMasksPhone() {
        String phone = registerOne();

        PageResult<UserView> page = userAdminService.list(1, 100);

        assertThat(page.getTotal()).isGreaterThan(0);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(100);
        assertThat(page.getPages()).isGreaterThanOrEqualTo(1);

        UserView hit = page.getRecords().stream()
                .filter(v -> phone.substring(0, 3).equals(v.getPhone() == null ? "" : v.getPhone().substring(0, 3)))
                .findFirst().orElse(null);
        assertThat(hit).as("新注册用户应出现在列表中").isNotNull();
        assertThat(hit.getPhone()).isEqualTo(phone.substring(0, 3) + "****" + phone.substring(7));
    }

    @Test
    @DisplayName("分页边界：页码小于 1 时归一为 1，页大小至少为 1")
    void listNormalizesPagingParams() {
        PageResult<UserView> page = userAdminService.list(0, 0);

        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1);
        assertThat(page.getRecords()).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("删除用户：首次成功，重复删除返回 false")
    void deleteIsIdempotent() {
        String phone = registerOne();
        Long id = userMapper.selectByPhone(phone).getId();

        assertThat(userAdminService.delete(id)).isTrue();
        assertThat(userMapper.selectById(id)).isNull();
        assertThat(userAdminService.delete(id)).as("已删除的用户再次删除应返回 false").isFalse();
    }

    @Test
    @DisplayName("删除不存在的用户不抛异常")
    void deleteMissingUser() {
        assertThat(userAdminService.delete(999_999_999L)).isFalse();
    }
}
