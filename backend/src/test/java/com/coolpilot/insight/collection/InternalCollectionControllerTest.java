package com.coolpilot.insight.collection;

import com.coolpilot.insight.collection.application.DedupQueryService;
import com.coolpilot.insight.collection.controller.InternalCollectionController;
import com.coolpilot.insight.mapper.CommentMapper;
import com.coolpilot.insight.service.VideoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部去重接口的鉴权（U1 · A-API）。
 *
 * <p>`/internal/**` 不在 JWT 拦截器的 `addPathPatterns("/api/**")` 范围内，
 * 所以**这个接口的安全性完全由本类的令牌检查决定**。三条断言分别对应：
 * 令牌配了且对、令牌配了但错、令牌没配（必须拒绝，不能"没配就不校验"）。
 */
class InternalCollectionControllerTest {

    private static DedupQueryService service() {
        CommentMapper mapper = (CommentMapper) Proxy.newProxyInstance(
                CommentMapper.class.getClassLoader(),
                new Class<?>[]{CommentMapper.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "countDedupRecords":
                            return 0L;
                        case "selectDedupPage":
                            return List.of();
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        VideoService videoService = new VideoService(null, null, null, null) {
            @Override
            public Long resolveInternalId(String raw) {
                return null;
            }
        };
        return new DedupQueryService(mapper, videoService);
    }

    private static MockHttpServletRequest req(String token) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/internal/collection/dedup");
        if (token != null) {
            r.addHeader("X-Internal-Token", token);
        }
        return r;
    }

    @Test
    @DisplayName("令牌正确 → 200")
    void correctTokenPasses() {
        InternalCollectionController c = new InternalCollectionController(service(), "s3cret");

        var resp = c.dedup("123", 0, 10, req("s3cret"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getData()).isNotNull();
    }

    @Test
    @DisplayName("令牌错误 / 缺失 → 401")
    void wrongOrMissingTokenRejected() {
        InternalCollectionController c = new InternalCollectionController(service(), "s3cret");

        assertThat(c.dedup("123", 0, 10, req("nope")).getStatusCode().value()).isEqualTo(401);
        assertThat(c.dedup("123", 0, 10, req(null)).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("未配置令牌 → 401（fail-closed，不是放行）")
    void unconfiguredTokenRejects() {
        InternalCollectionController c = new InternalCollectionController(service(), "");

        assertThat(c.dedup("123", 0, 10, req(null)).getStatusCode().value()).isEqualTo(401);
        assertThat(c.dedup("123", 0, 10, req("")).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("externalContentId 为空 → 400（鉴权通过之后才校验参数）")
    void blankContentIdRejected() {
        InternalCollectionController c = new InternalCollectionController(service(), "s3cret");

        assertThat(c.dedup("  ", 0, 10, req("s3cret")).getStatusCode().value()).isEqualTo(400);
    }
}
