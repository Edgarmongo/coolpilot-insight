package com.coolpilot.insight.system;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.web.PageResult;
import com.coolpilot.insight.system.application.AuditQueryService;
import com.coolpilot.insight.system.application.ConfigAdminService;
import com.coolpilot.insight.system.application.ConfigService;
import com.coolpilot.insight.system.domain.SystemConfig;
import com.coolpilot.insight.system.dto.AuditLogView;
import com.coolpilot.insight.system.dto.ConfigItemView;
import com.coolpilot.insight.system.infrastructure.persistence.SystemConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统配置管理（R2-03）。
 *
 * <p>三条必须守住的规则：密钥配置不回显明文、更新后缓存立即失效、值类型不合法要拒绝。
 * 前两条若写错，分别是"泄露密钥"和"改了配置不生效"——都不会报错，只会静默出错。
 */
class SystemConfigTest extends AbstractIntegrationTest {

    @Autowired
    private ConfigAdminService configAdminService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private SystemConfigMapper configMapper;

    @Autowired
    private AuditQueryService auditQueryService;

    private SystemConfig insertConfig(String key, String value, String type, int secret) {
        SystemConfig c = new SystemConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setValueType(type);
        c.setIsSecret(secret);
        c.setDescription("单元测试插入");
        configMapper.insert(c);
        return c;
    }

    @Test
    @DisplayName("密钥类配置不回显明文，只返回是否已配置")
    void secretValuesAreMasked() {
        insertConfig("test.secret.key", "super-secret-value", "STRING", 1);
        configService.invalidate();

        List<ConfigItemView> list = configAdminService.list();
        ConfigItemView hit = list.stream()
                .filter(v -> "test.secret.key".equals(v.getKey()))
                .findFirst().orElse(null);

        assertThat(hit).isNotNull();
        assertThat(hit.isSecret()).isTrue();
        assertThat(hit.getValue()).as("密钥值绝不能出现在响应中").isNull();
        assertThat(hit.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("更新配置后本地缓存立即失效（否则 60 秒内读到旧值）")
    void updateInvalidatesCache() {
        String key = "test.cache.key";
        insertConfig(key, "old-value", "STRING", 0);

        // 先读一次，让缓存装载
        assertThat(configService.get(key)).isEqualTo("old-value");

        configAdminService.update(key, "new-value");

        assertThat(configService.get(key))
                .as("更新后必须立即读到新值")
                .isEqualTo("new-value");
    }

    @Test
    @DisplayName("值类型不匹配时拒绝更新")
    void updateRejectsWrongType() {
        String key = "test.int.key";
        insertConfig(key, "10", "INT", 0);

        assertThatThrownBy(() -> configAdminService.update(key, "not-a-number"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INT");
    }

    @Test
    @DisplayName("更新不存在的配置项被拒绝")
    void updateMissingKeyRejected() {
        assertThatThrownBy(() -> configAdminService.update("no.such.key", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("空值被拒绝")
    void updateBlankRejected() {
        String key = "test.blank.key";
        insertConfig(key, "v", "STRING", 0);

        assertThatThrownBy(() -> configAdminService.update(key, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("审计查询：分页结构正确，且能按动作过滤")
    void auditLogPaging() {
        // 借配置更新产生一条 CONFIG_UPDATE 审计
        String key = "test.audit.key";
        insertConfig(key, "a", "STRING", 0);
        configAdminService.update(key, "b");

        PageResult<AuditLogView> all = auditQueryService.page(null, null, 1, 10);
        assertThat(all.getTotal()).isGreaterThan(0);
        assertThat(all.getCurrent()).isEqualTo(1);
        assertThat(all.getSize()).isEqualTo(10);

        PageResult<AuditLogView> filtered = auditQueryService.page("CONFIG_UPDATE", null, 1, 10);
        assertThat(filtered.getRecords())
                .isNotEmpty()
                .allMatch(v -> "CONFIG_UPDATE".equals(v.getAction()));
    }

    @Test
    @DisplayName("审计查询：页码与页大小做边界归一")
    void auditPagingNormalized() {
        PageResult<AuditLogView> page = auditQueryService.page(null, null, 0, 0);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1);
    }
}
