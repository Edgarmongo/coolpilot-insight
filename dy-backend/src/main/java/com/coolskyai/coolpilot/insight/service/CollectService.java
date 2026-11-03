package com.coolskyai.coolpilot.insight.service;

import java.util.Map;

public interface CollectService {
    void startCollect(String videoId, int maxComments);

    void startCollect(String videoId, int maxComments, String collectMode);

    Map<String, String> getStatus(String videoId);
}
