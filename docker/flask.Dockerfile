# ============================================================
# Flask 分词服务镜像：词云分词 + DeepSeek AI 话题分析
#
# 独立成服务的好处：Spring Boot 无需内嵌 Python 解释器即可获得词云能力，
# 且 jieba 词典预加载只需在启动时做一次。
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

COPY Py_Data/clean/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && rm requirements.txt

COPY Py_Data/clean/ ./clean/

EXPOSE 5000

# 监听 0.0.0.0 才能被其他容器访问（容器间通过服务名互访）
ENV FLASK_HOST=0.0.0.0 \
    FLASK_PORT=5000

CMD ["python", "clean/wordcloud_service.py"]
