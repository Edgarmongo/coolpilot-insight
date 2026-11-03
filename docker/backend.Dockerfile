# ============================================================
# 后端镜像：Spring Boot + Python + Chrome
#
# 三者缺一不可：
#   - JDK 8        运行 Spring Boot（pom 指定 java.version=1.8）
#   - Python 3     运行爬虫(douyin_comment.py)与清洗(clean_main.py)脚本
#   - Chrome       供 DrissionPage 驱动完成抖音评论采集
#
# Chrome 采用官方 deb 包（Ubuntu 22.04 的 chromium 为 snap 包，
# 无法在容器内工作，故不用 apt 安装 chromium）。
# ============================================================

# ---------- 构建阶段：编译打包 ----------
FROM maven:3.9-eclipse-temurin-8 AS builder

WORKDIR /build

# 先复制 pom 单独解析依赖，利用 Docker 层缓存：源码变动时无需重下依赖
COPY dy-backend/pom.xml ./pom.xml
COPY docker/maven-settings.xml /root/.m2/settings.xml
RUN mvn -B -f pom.xml dependency:go-offline

COPY dy-backend/src ./src
RUN mvn -B -f pom.xml clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:8-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    # 国内网络：pip 走阿里云镜像
    PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
    TRUSTED_HOST=mirrors.aliyun.com

# 换用国内 apt 源（保留原源作为回退）
RUN sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g; \
            s|http://security.ubuntu.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g' \
        /etc/apt/sources.list

# Python3 与 Chromium 运行依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-venv \
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
    && rm -rf /var/lib/apt/lists/*

# 安装 Google Chrome（DrissionPage 需要真实的 Chromium 内核）。
# 走 apt 而非直接下载 .deb，可自动解析依赖且不受大文件下载中断影响。
# 注：使用 --http1.1 与重试参数，避免 HTTP/2 大文件流中断导致下载失败。
RUN curl --http1.1 -fsSL --retry 5 --retry-delay 3 --retry-all-errors \
        -o /tmp/chrome.deb \
        https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get update \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb \
    && rm -rf /var/lib/apt/lists/* \
    && google-chrome --version

# Python 依赖装进独立 venv，避免污染系统 Python
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY Py_Data/clean/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt && rm /tmp/requirements.txt

# 应用代码
WORKDIR /app
COPY --from=builder /build/target/dy-comment-1.0.0.jar app.jar
COPY Py_Data /app/Py_Data

# 采集数据目录（挂载卷后此处为持久化位置）
RUN mkdir -p /app/Py_Data/Datas /app/chrome-data

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
