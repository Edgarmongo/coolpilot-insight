package com.coolskyai.coolpilot.insight.service;

import java.util.List;
import java.util.Map;

public interface CleanService {

    List<Map<String, String>> getPendingFiles();

    Map<String, Object> runClean(List<String> fileNames);

    /** 调度器/任务中心使用：显式指定操作人，不依赖 RequestContext */
    Map<String, Object> runClean(List<String> fileNames, Long operatorUserId);

    byte[] exportFiles(List<String> fileNames);
}
