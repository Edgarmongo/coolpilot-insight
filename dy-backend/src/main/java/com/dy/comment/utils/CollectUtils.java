package com.dy.comment.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CollectUtils {

    private static final Pattern MEDIA_ID_PATTERN = Pattern.compile("/(?:video|note|share/video)/(\\d+)");
    private static final Pattern MODAL_ID_PATTERN = Pattern.compile("[?&]modal_id=(\\d+)");

    /**
     * 从各种抖音输入中提取视频 ID。
     *
     * <p>兼容场景：</p>
     * <ul>
     *   <li>纯数字 ID</li>
     *   <li>视频页链接 /video/&lt;id&gt;</li>
     *   <li>图文链接 /note/&lt;id&gt;</li>
     *   <li>分享链接 /share/video/&lt;id&gt;</li>
     *   <li>搜索结果页点开后的链接 ?modal_id=&lt;id&gt;</li>
     * </ul>
     */
    public static String extractVideoId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new RuntimeException("请输入视频链接或编号");
        }
        raw = raw.trim();

        // 1. 纯数字 → 直接返回
        if (raw.matches("\\d+")) {
            return raw;
        }

        // 2. 查询参数 modal_id（从搜索结果页点开视频后地址栏复制来的链接）
        Matcher modal = MODAL_ID_PATTERN.matcher(raw);
        if (modal.find()) {
            return modal.group(1);
        }

        // 3. 包含 /video/数字 或 /note/数字 的长链接 → 正则提取
        Matcher m = MEDIA_ID_PATTERN.matcher(raw);
        if (m.find()) {
            return m.group(1);
        }

        // 4. 包含 douyin.com 的链接（短链接等）→ 原样传给 Python 让浏览器处理重定向
        if (raw.contains("douyin.com")) {
            return raw;
        }

        throw new RuntimeException("无法识别视频编号，请检查链接是否正确");
    }
}
