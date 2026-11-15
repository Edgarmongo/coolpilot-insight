package com.coolpilot.insight.integration.ai;

import com.coolpilot.insight.integration.common.CommandResult;

import java.util.List;

/** AI/清洗服务客户端。当前为子进程实现，R4 换成 HTTP。 */
public interface AiClient {

    /** 清洗指定 CSV 文件并入库，operatorUserId 用于审计归属。 */
    CommandResult cleanFiles(List<String> csvFileNames, Long operatorUserId);
}
