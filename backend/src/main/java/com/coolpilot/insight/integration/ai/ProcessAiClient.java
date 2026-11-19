package com.coolpilot.insight.integration.ai;

import com.coolpilot.insight.integration.common.CommandResult;
import com.coolpilot.insight.integration.common.ProcessRunner;
import com.coolpilot.insight.integration.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProcessAiClient implements AiClient {

    private static final int CLEAN_TIMEOUT_SECONDS = 300;

    private final AppProperties props;
    private final ProcessRunner runner;

    public ProcessAiClient(AppProperties props, ProcessRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    @Override
    public CommandResult cleanFiles(List<String> csvFileNames, Long operatorUserId) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getPythonExe());
        cmd.add("clean.py");
        cmd.addAll(csvFileNames);

        Map<String, String> env = new HashMap<>();
        if (operatorUserId != null) {
            env.put("CLEAN_USER_ID", operatorUserId.toString());
        }
        return runner.run(cmd, props.getPythonCleanDir(), env, CLEAN_TIMEOUT_SECONDS);
    }
}
