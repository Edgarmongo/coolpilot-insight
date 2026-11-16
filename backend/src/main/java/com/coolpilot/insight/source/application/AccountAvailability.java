package com.coolpilot.insight.source.application;

/**
 * 账号可用性查询——核对 {@code ACCOUNT_REQUIRED} 所需的唯一输入。
 *
 * <p>抽成接口而不直接依赖 Mapper，有两个理由：
 * <ol>
 *   <li>核对器保持纯函数式，测试可以直接传 lambda，不必起数据库；</li>
 *   <li>"可用账号"的判定口径（状态 + 授权 + 有效期）将来可能变化，
 *       收在一个实现里比散落在核对逻辑中好改。</li>
 * </ol>
 */
@FunctionalInterface
public interface AccountAvailability {

    /**
     * 该数据源下是否存在**可用**账号。
     *
     * <p>"可用"的定义：{@code status=ACTIVE}（管理员启用）
     * 且 {@code auth_status=VALID}（授权有效）
     * 且未过期（{@code expires_at} 为空或晚于当前时间）。
     *
     * <p>注意两个状态不可合并：管理员停用与授权过期是不同的问题，
     * 但**对"能否执行"而言结论相同**——都不能用。
     *
     * @param sourceId 数据源 id；为 null 时返回 false
     */
    boolean hasUsableAccount(Long sourceId);
}
