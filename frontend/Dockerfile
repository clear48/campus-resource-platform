FROM node:24-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS builder

WORKDIR /workspace

# package-lock.json 与 npm ci 保证容器构建使用锁定依赖。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
ENV VITE_API_BASE_URL=/api/v1
RUN npm run build

# 非特权 Nginx 默认监听 8080，避免容器为绑定低端口而以 root 身份运行。
FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:04a3275f25d766cff8926d2e57b2ff34a783d6b12a702dc98bb82226d2d9a508

COPY --from=builder --chown=101:101 /workspace/dist /usr/share/nginx/html

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/healthz | grep -q '^ok$' || exit 1
