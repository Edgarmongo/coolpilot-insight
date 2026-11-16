package com.coolpilot.insight.collection;

import com.coolpilot.insight.collection.application.DedupQueryService;
import com.coolpilot.insight.collection.application.DedupQueryService.DedupPage;
import com.coolpilot.insight.mapper.CommentMapper;
import com.coolpilot.insight.service.VideoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 去重记忆查询（U1 · A-API）。
 *
 * <p>重点不是"能查出来"，而是**能不能让调用方判断自己拿到的集合是否完整**：
 * `nextCursor` 与 `truncated` 两者缺一，调用方就会把残缺集合当成全量，
 * 然后重复采集——那正是这次改造要消除的问题。
 */
class DedupQueryServiceTest {

    private static final String AWEME = "7703721523349239951";

    // ------------------------------------------------------------------
    @Test
    @DisplayName("视频还没入库 → 返回空页，不是错误")
    void unknownVideoReturnsEmpty() {
        DedupQueryService service = service(null, 0, List.of());

        DedupPage page = service.query(AWEME, 0, 1000);

        assertThat(page.total()).isZero();
        assertThat(page.cids()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.truncated()).isFalse();
    }

    @Test
    @DisplayName("两类键分别归位：CID 行进 cids，FINGERPRINT 行进 fingerprints")
    void mapsBothStrategies() {
        List<Map<String, Object>> rows = List.of(
                row("7703837650353177401", "7703837650353177401", "CID"),
                row(null, "a".repeat(40), "FINGERPRINT"));

        DedupPage page = service(1L, 2, rows).query(AWEME, 0, 1000);

        assertThat(page.cids()).containsExactly("7703837650353177401");
        assertThat(page.fingerprints()).containsExactly("a".repeat(40));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("还有剩余时给出 nextCursor，且正好是已消费条数")
    void nextCursorPointsToRemainder() {
        List<Map<String, Object>> rows = List.of(
                row("c1", "c1", "CID"),
                row("c2", "c2", "CID"));

        DedupPage page = service(1L, 10, rows).query(AWEME, 0, 2);

        assertThat(page.nextCursor()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(10);
    }

    @Test
    @DisplayName("超过扫描上限 → truncated=true，调用方必须视为拿不到完整集合")
    void truncatedWhenTooLarge() {
        List<Map<String, Object>> rows = List.of(row("c1", "c1", "CID"));

        DedupPage page = service(1L, DedupQueryService.MAX_SCAN + 1, rows).query(AWEME, 0, 1000);

        assertThat(page.truncated()).isTrue();
        assertThat(page.total()).isEqualTo(DedupQueryService.MAX_SCAN + 1);
    }

    @Test
    @DisplayName("limit 被夹到上限，防止用超大 limit 变相全量拉取")
    void limitIsClamped() {
        CapturingMapper capture = new CapturingMapper(1L, 1, List.of(row("c1", "c1", "CID")));
        new DedupQueryService(capture.mapper(), videoService(1L))
                .query(AWEME, 0, 999_999);

        assertThat(capture.lastLimit).isEqualTo(DedupQueryService.MAX_LIMIT);
    }

    @Test
    @DisplayName("负数游标不报错，按 0 处理")
    void negativeCursorIsSafe() {
        DedupPage page = service(1L, 1, List.of(row("c1", "c1", "CID"))).query(AWEME, -5, 1000);

        assertThat(page.cids()).containsExactly("c1");
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------
    private static DedupQueryService service(Long videoId, long total,
                                             List<Map<String, Object>> rows) {
        return new DedupQueryService(new CapturingMapper(videoId, total, rows).mapper(),
                videoService(videoId));
    }

    private static VideoService videoService(Long videoId) {
        return new VideoService(null, null, null, null) {
            @Override
            public Long resolveInternalId(String raw) {
                return videoId;
            }
        };
    }

    private static Map<String, Object> row(String cid, String dedupKey, String strategy) {
        Map<String, Object> m = new HashMap<>();
        m.put("cid", cid);
        m.put("dedupKey", dedupKey);
        m.put("dedupStrategy", strategy);
        return m;
    }

    /** 用动态代理实现注释里只用到的那两个方法，避免为 25 个方法写空实现。 */
    private static final class CapturingMapper {
        private final Long videoId;
        private final long total;
        private final List<Map<String, Object>> rows;
        int lastLimit = -1;

        CapturingMapper(Long videoId, long total, List<Map<String, Object>> rows) {
            this.videoId = videoId;
            this.total = total;
            this.rows = new ArrayList<>(rows);
        }

        CommentMapper mapper() {
            return (CommentMapper) Proxy.newProxyInstance(
                    CommentMapper.class.getClassLoader(),
                    new Class<?>[]{CommentMapper.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "countDedupRecords":
                                return total;
                            case "selectDedupPage":
                                lastLimit = (Integer) args[2];
                                return rows;
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
