package com.coolskyai.coolpilot.insight.service;

import java.util.Map;

public interface CommentService {

    /** 分页返回 {records, total, page, size} */
    Map<String, Object> listByVideo(String videoId, int page, int size,
                                    String region, Integer sentiment, String sort);

    Map<String, Object> search(String keyword, String videoId, int page, int size);
}
