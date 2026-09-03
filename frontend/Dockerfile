# 建置階段：產出 dist
FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

# .dockerignore 擋掉宿主的 node_modules 與測試產物，npm ci 的乾淨依賴才不會被覆蓋
COPY . .
RUN npm run build

# 服務階段：nginx 供靜態檔並反代 API 與 WebSocket（設定見 nginx.conf；
# 自寫 Node proxy 缺 upgrade 事件處理，聊天室 WebSocket 過不去，故採 nginx）
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
