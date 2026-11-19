package com.coolpilot.insight;

import com.coolpilot.insight.system.dto.LoginResponse;
import com.coolpilot.insight.system.domain.User;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册与登录链路。
 *
 * <p>重点保护三条约束：
 * 1. 手机号唯一（改到原生 MyBatis 后，这条依赖 selectByPhone 的正确实现）
 * 2. 哨兵密码账号不可登录（ADR-005：库泄露也拿不到入口）
 * 3. 密码用 BCrypt 校验，明文绝不落库
 */
class UserAuthFlowTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "Passw0rd!1234";

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private String nextPhone() {
        // 11 位、1 开头，保证通过手机号格式校验
        return "139" + String.format("%08d", 10_000_000 + SEQ.getAndIncrement());
    }

    @Test
    @DisplayName("注册后可登录，且拿到 JWT")
    void registerThenLogin() {
        String phone = nextPhone();
        userService.register(phone, PASSWORD, PASSWORD);

        LoginResponse resp = userService.login(phone, PASSWORD);

        assertThat(resp).isNotNull();
        assertThat(resp.getToken()).isNotBlank();
    }

    @Test
    @DisplayName("密码以 BCrypt 存储，不是明文")
    void passwordIsHashed() {
        String phone = nextPhone();
        userService.register(phone, PASSWORD, PASSWORD);

        User user = userMapper.selectByPhone(phone);
        assertThat(user).isNotNull();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(user.getPasswordHash()).startsWith("$2");
    }

    @Test
    @DisplayName("重复手机号注册被拒绝")
    void duplicatePhoneRejected() {
        String phone = nextPhone();
        userService.register(phone, PASSWORD, PASSWORD);

        assertThatThrownBy(() -> userService.register(phone, PASSWORD, PASSWORD))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被注册")
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE);
    }

    @Test
    @DisplayName("密码错误登录被拒绝")
    void wrongPasswordRejected() {
        String phone = nextPhone();
        userService.register(phone, PASSWORD, PASSWORD);

        assertThatThrownBy(() -> userService.login(phone, "WrongPass!999"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CREDENTIALS_INVALID);
    }

    @Test
    @DisplayName("两次密码不一致时注册失败")
    void confirmMismatchRejected() {
        assertThatThrownBy(() -> userService.register(nextPhone(), PASSWORD, "Another!1234"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("哨兵密码账号无法登录（即便密码为空）")
    void sentinelAccountCannotLogin() {
        // V6 种子写入的 00000000001 使用哨兵值，未初始化前任何密码都不该通过
        assertThatThrownBy(() -> userService.login("00000000001", "anything"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }
}
