package com.coolskyai.coolpilot.insight.service;

import java.util.List;
import java.util.Map;

public interface AnalysisService {
    Map<String, Object> getSummary(String videoId);

    List<Map<String, Object>> getRegionStats(String videoId);

    List<Map<String, Object>> getSentimentStats(String videoId);

    List<Map<String, Object>> getTrendStats(String videoId);

    List<Map<String, Object>> getTopComments(String videoId);

    List<Map<String, Object>> getVideoList();

    List<Map<String, Object>> getWordCloud(String videoId);

    Map<String, Object> getSankey(String videoId);

    String getTopic(String videoId);

    /** 清空缓存并返回最新统计口径，前端随后重新拉取各维度 */
    Map<String, Object> refreshAnalysis(String videoId);
}
