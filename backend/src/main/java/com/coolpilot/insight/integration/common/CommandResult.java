package com.coolpilot.insight.integration.common;

/** 子进程/外部命令的执行结果。output 为 stdout 与 stderr 合并后的内容。 */
public record CommandResult(int exitCode, String output, long durationMs) {

    public boolean success() {
        return exitCode == 0;
    }

    /** 取输出尾部若干字符，用于日志与错误提示（完整输出可能很长）。 */
    public String tail(int max) {
        if (output == null) {
            return "";
        }
        return output.length() <= max ? output : output.substring(output.length() - max);
    }
}
