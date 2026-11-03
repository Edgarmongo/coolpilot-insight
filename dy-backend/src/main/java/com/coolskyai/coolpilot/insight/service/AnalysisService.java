package com.coolskyai.coolpilot.insight.service;

import com.coolskyai.coolpilot.insight.entity.AnalysisResult;

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
    AnalysisResult refreshAnalysis(String videoId);
}
