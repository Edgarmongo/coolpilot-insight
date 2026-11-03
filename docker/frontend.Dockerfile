# ============================================================
# 前端镜像：Node 构建 -> Nginx 托管
#
# 生产环境无需 Vite dev server，Nginx 直接托管静态产物并反代 /api，
# 前端因此与后端同源，无需处理 CORS。
# ============================================================

# ---------- 构建阶段 ----------
FROM node:18-alpine AS builder

WORKDIR /app

# 国内网络走 npmmirror
RUN npm config set registry https://registry.npmmirror.com

COPY dy-frontend/package.json ./
RUN npm install

COPY dy-frontend/ ./
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:alpine

ENV TZ=Asia/Shanghai

COPY --from=builder /app/dist /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
