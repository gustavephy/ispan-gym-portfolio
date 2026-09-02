# Build stage
FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# Run stage using node:20-alpine (cached locally)
FROM node:20-alpine
WORKDIR /app

COPY --from=builder /app/dist ./dist
COPY server.js ./server.js

EXPOSE 80
CMD ["node", "server.js"]
