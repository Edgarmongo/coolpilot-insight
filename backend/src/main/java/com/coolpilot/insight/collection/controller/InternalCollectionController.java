package com.coolpilot.insight.collection.controller;

import com.coolpilot.insight.collection.application.DedupQueryService;
import com.coolpilot.insight.common.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口：跨次去重记忆（U1 · A-API）。
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>路径在 {@code /internal/**} 下——**不走 JWT 拦截器**（它只管 {@code /api/**}），
 *       所以这里必须自己做鉴权，否则就是一个对外的裸接口。</li>
 *   <li>鉴权用与采集服务之间共享的 {@code X-Internal-Token}（不新增凭据类型），
 *       比较用 {@link MessageDigest#isEqual} 做定长比较，避免逐字节短路泄露。</li>
 *   <li>只读、只返回 cid 与指纹，不返回评论正文、昵称等任何内容。</li>
 * </ul>
 *
 * <h3>未配置令牌时一律拒绝</h3>
 * 早期实现是"未配置就放行 + 打 WARN"，但 8080 是映射到宿主/内网的，
 * 那等于给任何能访问该端口的人开了一个读评论 id 的接口。
 * 现在改为**未配置即 401**：本地开发也必须显式设置 {@code COLLECTION_INTERNAL_TOKEN}。
 */
@RestController
@RequestMapping("/internal/collection")
public class InternalCollectionController {

    private static final Logger log = LoggerFactory.getLogger(InternalCollectionController.class);

    private final DedupQueryService dedupQueryService;
    private final String internalToken;

    public InternalCollectionController(
            DedupQueryService dedupQueryService,
            @Value("${app.collection-internal-token:}") String internalToken) {
        this.dedupQueryService = dedupQueryService;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @GetMapping("/dedup")
    public ResponseEntity<Result<DedupQueryService.DedupPage>> dedup(
            @RequestParam("externalContentId") String externalContentId,
            @RequestParam(value = "cursor", defaultValue = "0") int cursor,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            HttpServletRequest request) {

        if (!authorized(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.fail(40100, "内部令牌无效"));
        }
        if (externalContentId == null || externalContentId.isBlank()) {
            return ResponseEntity.badRequest().body(Result.fail(40000, "externalContentId 不能为空"));
        }
        return ResponseEntity.ok(Result.ok(
                dedupQueryService.query(externalContentId.trim(), cursor, limit)));
    }

    private boolean authorized(HttpServletRequest request) {
        if (internalToken.isEmpty()) {
            // fail-closed：配置缺失不等于"没有要求"，否则任何人都能查走评论 id
            log.warn("[internal] 未配置 app.collection-internal-token，去重接口一律拒绝（401）。"
                    + "本地开发请显式设置 COLLECTION_INTERNAL_TOKEN。");
            return false;
        }
        String presented = request.getHeader("X-Internal-Token");
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }
}
