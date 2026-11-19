# ============================================================
# 采集服务镜像（方案 A：独立容器）
#
# 职责：
#   - 对外提供 /health 与 /v1/capabilities 等 Registry 查询接口
#   - 承载各平台 Adapter 的执行环境（Chrome + DrissionPage）
#
# 与后端容器的边界：
#   backend  —— 业务编排、任务调度、结果入库
#   collection —— 平台接入能力与 Registry
#
# 两者共享同一个 CSV 数据目录（bind mount），因此后端读 CSV、
# 采集服务写 CSV 的现有生产链路在容器化后依然成立。
#
# Chrome 采用官方 deb 包（Debian 的 chromium 为 snap 包，容器内不可用）。
# ============================================================

FROM python:3.12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
    TRUSTED_HOST=mirrors.aliyun.com

WORKDIR /app

# 换用国内 apt 源（保留原源作为回退）
RUN sed -i 's|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g; \
            s|http://security.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g' \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true

# Chrome 与中文字体运行依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        wget \
        fonts-liberation \
        fonts-noto-cjk \
        libnss3 \
        libatk1.0-0 \
        libatk-bridge2.0-0 \
        libcups2 \
        libdrm2 \
        libxkbcommon0 \
        libxcomposite1 \
        libxdamage1 \
        libxfixes3 \
        libxrandr2 \
        libgbm1 \
        libasound2 \
        libpango-1.0-0 \
        libcairo2 \
        libatspi2.0-0 \
        libx11-6 \
        libxcb1 \
        libxext6 \
    && rm -rf /var/lib/apt/lists/*

# 安装 Google Chrome（DrissionPage 需要真实的 Chromium 内核）。
# 使用 --http1.1 与重试参数，避免 HTTP/2 大文件流中断导致下载失败。
RUN curl --http1.1 -fsSL --retry 5 --retry-delay 3 --retry-all-errors \
        -o /tmp/chrome.deb \
        https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get update \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb \
    && rm -rf /var/lib/apt/lists/* \
    && google-chrome --version

# Python 依赖装进独立 venv
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY collection-service/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt && rm /tmp/requirements.txt

# 应用代码
COPY collection-service /app/collection-service

# 采集数据目录（与 backend 共享同一宿主目录）
RUN mkdir -p /app/collection-service/datas /app/chrome-data

ENV COLLECTION_PORT=5001 \
    CHROME_HEADLESS=1 \
    CHROME_BROWSER_PATH=/usr/bin/google-chrome \
    CHROME_USER_DATA_DIR=/app/chrome-data \
    CHROME_NO_SANDBOX=1 \
    PY_DATAS_DIR=/app/collection-service/datas

EXPOSE 5001

# 服务入口在 collection-service/app/server.py。
# 该文件会把 collection-service 目录插入 sys.path，因此 `from app.registry...` 可正常导入。
CMD ["/opt/venv/bin/python", "collection-service/app/server.py"]
