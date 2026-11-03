package com.dy.comment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 运行时路径与外部服务地址配置。
 *
 * <p>所有路径均可通过环境变量覆盖，使同一份代码既能以 Windows 本地开发方式运行，
 * 也能在 Docker 容器中运行。默认值保持与改造前的本地行为一致。</p>
 *
 * <ul>
 *   <li>{@code PYTHON_EXE}      —— Python 解释器路径（默认使用项目内 .venv）</li>
 *   <li>{@code PY_DATA_DIR}     —— Py_Data 目录（爬虫/清洗脚本所在目录）</li>
 *   <li>{@code PY_CLEAN_DIR}    —— 清洗脚本目录</li>
 *   <li>{@code PY_DATAS_DIR}    —— 采集 CSV 数据目录</li>
 *   <li>{@code FLASK_BASE_URL}  —— Flask 分词服务地址</li>
 * </ul>
 */
@Component
public class AppProperties {

    /** 项目根目录（本地默认为当前工作目录的上一级）。 */
    private final String projectRoot;

    private final String pythonExe;
    private final String pythonDataDir;
    private final String pythonCleanDir;
    private final String datasDir;
    private final String flaskBaseUrl;

    public AppProperties(
            @Value("${app.project-root:}") String projectRoot,
            @Value("${app.python-exe:}") String pythonExe,
            @Value("${app.python-data-dir:}") String pythonDataDir,
            @Value("${app.python-clean-dir:}") String pythonCleanDir,
            @Value("${app.datas-dir:}") String datasDir,
            @Value("${app.flask-base-url:}") String flaskBaseUrl) {
        this.projectRoot = resolveRoot(projectRoot);
        this.pythonExe = orDefault(pythonExe, defaultPythonExe(this.projectRoot));
        this.pythonDataDir = orDefault(pythonDataDir, this.projectRoot + "/Py_Data");
        this.pythonCleanDir = orDefault(pythonCleanDir, this.pythonDataDir + "/clean");
        this.datasDir = orDefault(datasDir, this.pythonDataDir + "/Datas");
        this.flaskBaseUrl = orDefault(flaskBaseUrl, "http://127.0.0.1:5000");
    }

    private static String resolveRoot(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return trimTrailingSlash(configured.trim());
        }
        return trimTrailingSlash(System.getProperty("user.dir") + "/..");
    }

    /**
     * 默认 Python 解释器：优先使用 Windows 下的 .venv\Scripts\python.exe，
     * 其次尝试 POSIX 布局的 .venv/bin/python，最后退回系统 python。
     */
    private static String defaultPythonExe(String root) {
        String win = root + "/.venv/Scripts/python.exe";
        String posix = root + "/.venv/bin/python";
        if (new java.io.File(win).exists()) {
            return win;
        }
        if (new java.io.File(posix).exists()) {
            return posix;
        }
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? win
                : "python3";
    }

    private static String orDefault(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") || s.endsWith("\\")
                ? s.substring(0, s.length() - 1)
                : s;
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    public String getPythonExe() {
        return pythonExe;
    }

    public String getPythonDataDir() {
        return pythonDataDir;
    }

    public String getPythonCleanDir() {
        return pythonCleanDir;
    }

    public String getDatasDir() {
        return datasDir;
    }

    public String getFlaskBaseUrl() {
        return flaskBaseUrl;
    }
}
