package com.coolpilot.insight.source.infrastructure.credential;

/**
 * 凭证读取抽象。
 *
 * <p>业务层只认引用（如 {@code local-secret:account/dy-main}），不关心底层是本地加密文件还是 Vault。
 * 首期只有本地实现；未来接 Vault 时新增一个实现类即可，调用方不变。
 *
 * <p><b>诚实声明</b>：本阶段只实现"凭证是否存在"的判定，**加密存储尚未实现**。
 * 不得对外宣称已具备完整密管能力。
 */
public interface CredentialProvider {

    /**
     * 凭证存储本身是否可用（目录存在且可读）。
     *
     * <p>与"某个凭证不存在"是两个不同的问题：目录没挂载属部署异常，
     * 不能据此判定每个账号都无效。
     */
    boolean isStorageAvailable();

    /** 该引用是否已配置（不读取内容）。 */
    boolean isConfigured(String ref);

    /** 读取凭证明文，仅执行期调用；未配置返回 null。 */
    char[] resolve(String ref);
}
