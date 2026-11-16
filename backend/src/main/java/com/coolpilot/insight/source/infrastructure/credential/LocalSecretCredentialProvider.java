package com.coolpilot.insight.source.infrastructure.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地凭证实现：{@code local-secret:account/<key>} → {@code <secretDir>/account/<key>}。
 *
 * <p>只做"文件是否存在"与"读取内容"，不做解密——因为首期写的就是明文文件，
 * 部署时应由宿主机权限与卷挂载保护。**这一点必须在文档中如实标注，不能包装成密管。**
 */
@Component
public class LocalSecretCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalSecretCredentialProvider.class);
    private static final String PREFIX = "local-secret:";

    private final Path secretDir;

    public LocalSecretCredentialProvider(@Value("${app.secret-dir:./secrets}") String secretDir) {
        this.secretDir = Paths.get(secretDir).toAbsolutePath().normalize();
    }

    @Override
    public boolean isConfigured(String ref) {
        Path p = resolvePath(ref);
        return p != null && Files.isReadable(p);
    }

    @Override
    public char[] resolve(String ref) {
        Path p = resolvePath(ref);
        if (p == null || !Files.isReadable(p)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim().toCharArray();
        } catch (IOException e) {
            log.warn("[credential] 读取失败 ref={}", ref);
            return null;
        }
    }

    /** 防目录穿越：引用中的 .. 或绝对路径一律拒绝。 */
    private Path resolvePath(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            return null;
        }
        String rel = ref.substring(PREFIX.length());
        if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/") || rel.contains("\\")) {
            return null;
        }
        Path p = secretDir.resolve(rel).normalize();
        return p.startsWith(secretDir) ? p : null;
    }
}
