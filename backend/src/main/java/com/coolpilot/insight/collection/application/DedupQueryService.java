package com.coolpilot.insight.collection.application;

import com.coolpilot.insight.mapper.CommentMapper;
import com.coolpilot.insight.service.VideoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨次去重记忆的查询服务（U1 · A-API）。
 *
 * <h3>为什么是 API 而不是让采集服务直连数据库</h3>
 * `comment` 表已经是历史 CID 的权威来源，但把库凭据发给 collection 容器
 * 会把数据库访问主体从 1 个扩到 2 个（凭据分发、审计范围、爆炸半径全都变）。
 * 因此由 backend 提供**只读、分页、受限**的查询接口，采集侧通过内部令牌调用。
 *
 * <h3>为什么必须分页 + truncated</h3>
 * 单个视频的评论量理论上无上限。一次性返回全量会打爆响应体，
 * 而"悄悄截断"会让调用方以为去重集合完整——那正好会造成**重复采集**。
 * 所以这里同时给出 `total` / `nextCursor` / `truncated`：
 * 调用方能判断"我拿到的是不是全部"，拿不全时必须显式失败，而不是继续跑。
 */
@Service
public class DedupQueryService {

    private static final Logger log = LoggerFactory.getLogger(DedupQueryService.class);

    /** 单页默认条数。 */
    public static final int DEFAULT_LIMIT = 1000;
    /** 单页上限，防止调用方用一个超大 limit 变相全量拉取。 */
    public static final int MAX_LIMIT = 5000;
    /** 允许扫描的最大记录数；超过即 truncated=true，调用方必须视为"拿不到完整集合"。 */
    public static final long MAX_SCAN = 20_000L;

    private final CommentMapper commentMapper;
    private final VideoService videoService;

    public DedupQueryService(CommentMapper commentMapper, VideoService videoService) {
        this.commentMapper = commentMapper;
        this.videoService = videoService;
    }

    /**
     * 一页去重记录。
     *
     * @param cids        策略为 CID 的行（cid 非空）
     * @param fingerprints 策略为 FINGERPRINT 的行（dedup_key 即指纹）
     * @param nextCursor  下一页游标；{@code null} 表示已到末尾
     * @param total       该视频可用于去重的记录总数
     * @param truncated   {@code total > MAX_SCAN}：集合过大，本接口**不再提供**完整结果
     */
    public record DedupPage(List<String> cids, List<String> fingerprints,
                            Integer nextCursor, long total, boolean truncated) {

        public static DedupPage empty() {
            return new DedupPage(List.of(), List.of(), null, 0L, false);
        }
    }

    public DedupPage query(String externalContentId, int cursor, int limit) {
        int offset = Math.max(cursor, 0);
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // 视频还没入库（首次采集）→ 空集合，不是错误
        Long videoId = videoService.resolveInternalId(externalContentId);
        if (videoId == null) {
            return DedupPage.empty();
        }

        long total = commentMapper.countDedupRecords(videoId);
        if (total == 0) {
            return DedupPage.empty();
        }

        boolean truncated = total > MAX_SCAN;
        if (truncated && offset >= MAX_SCAN) {
            // 到上限就停：宁可明确"拿不到"，也不给一段看起来完整的残缺集合
            log.warn("[dedup] videoId={} total={} 超过扫描上限 {}，拒绝继续分页", videoId, total, MAX_SCAN);
            return new DedupPage(List.of(), List.of(), null, total, true);
        }

        List<Map<String, Object>> rows = commentMapper.selectDedupPage(videoId, offset, size);
        List<String> cids = new ArrayList<>(rows.size());
        List<String> fingerprints = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String strategy = str(row.get("dedupStrategy"));
            String cid = str(row.get("cid"));
            String key = str(row.get("dedupKey"));
            if ("FINGERPRINT".equals(strategy)) {
                if (!key.isEmpty()) {
                    fingerprints.add(key);
                }
            } else if (!cid.isEmpty()) {
                cids.add(cid);
            }
        }

        int consumed = offset + rows.size();
        Integer nextCursor = consumed < total ? consumed : null;
        return new DedupPage(cids, fingerprints, nextCursor, total, truncated);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
