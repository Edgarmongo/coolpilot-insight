package com.coolskyai.coolpilot.insight.service;

import java.util.Map;

public interface CommentService {

    /** 分页返回 {records, total, page, size} */
    Map<String, Object> listByVideo(String videoId, int page, int size,
                                    String region, Integer sentiment, String sort);

    Map<String, Object> search(String keyword, String videoId, int page, int size);

    /** 评论树：一级评论 + 嵌套回复（移植自 DYP 的 works/{id}/comments） */
    Map<String, Object> tree(String videoId, int limit, int rootLimit);
}
