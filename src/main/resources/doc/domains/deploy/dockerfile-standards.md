# Dockerfile 规范（统一构建/部署契约）

目标：让 Runner 在“拿到一个 git 仓库”后，不需要猜测项目类型，也不需要手工干预，就能稳定完成：

- `docker build` → 产出镜像
- `docker push` → 推到 Harbor（103）
- Runtime 拉取并运行 → `/apps/{appId}` 可访问

---

## 1. 最小统一约定（必须）

每个可部署仓库必须满足：

1) **仓库根目录存在 `Dockerfile`**
2) **容器监听端口契约**（二选一，建议全平台统一为 A）
   - **方案 A（推荐）**：容器永远监听 `8080`
   - 方案 B：容器读取 `PORT` 环境变量并监听（Runtime-Agent 需要注入 `PORT`）
3) **启动后必须提供 HTTP 服务**（至少 `/` 返回 200）

建议额外提供：

- `.dockerignore`：避免把 `node_modules/`、`dist/`、`.git/` 打进镜像
- `/internal/health`：健康检查（便于探活/告警/自动回滚）

---

## 2. 推荐目录结构（可选，但强烈建议）

```text
repo-root/
  Dockerfile
  .dockerignore
  deploy/
    app.yaml
  (app sources...)
```

`deploy/app.yaml`（可选）用于声明：

- `port: 8080`（若采用方案 A 可省略）
- `healthPath: /internal/health`

> 第一阶段可以不做 `app.yaml`，先用“统一端口 8080 + 默认 healthPath”跑通闭环。

---

## 3. 唯一推荐模板：前后端一体 Node 项目（你们现网统一类型）

你们平台已限制用户应用为“前后端一体的 Node 项目”，因此平台只需要维护**一套** Dockerfile 模板即可（避免分叉）。

### 3.1 统一约定（强制）

- **容器监听端口**：`8080`
- **启动命令**：`npm start`
- **健康检查（建议）**：`GET /internal/health` 返回 200

### 3.2 Dockerfile（推荐：多阶段 + 生产依赖最小化）

适用：Express/Nest/Next(standalone)/自研 Node Server（只要最终 `npm start` 能启动并监听 `8080`）。

```dockerfile
FROM node:20-bookworm-slim AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci

FROM node:20-bookworm-slim AS build
WORKDIR /app
COPY --from=deps /app/node_modules /app/node_modules
COPY . .
# 如果你的项目没有 build 步骤，可以删掉这一行
RUN npm run build || true

FROM node:20-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8080

# 生产依赖：如果你的项目把 build 产物输出到 dist/，保留 dist/ 与 package*.json 即可
COPY --from=build /app/package*.json /app/
COPY --from=build /app/node_modules /app/node_modules
COPY --from=build /app/dist /app/dist
# 如果你的项目是 SSR/全栈且需要更多文件（例如 .next/、public/、server.js），按需追加 COPY

EXPOSE 8080
CMD ["npm","start"]
```

应用侧要求：`npm start` 启动后监听 `process.env.PORT || 8080`。

---

## 补充约定：lockfile（package-lock.json）与构建环境的 registry 可达性

你们的 Runner（101）在构建镜像时执行 `npm ci`，npm 会优先使用 lockfile 中的 `resolved` 下载依赖 tarball。

因此请务必确保：

- **仓库提交的 `package-lock.json` 不要出现 `http://verdaccio:4873/...` 这类“仅容器网络可达”的地址**
- 推荐统一使用外网镜像源生成 lockfile：
  - `https://registry.npmmirror.com/`（推荐）
  - 或 `https://registry.npmjs.org/`

否则会出现典型故障：Runner 构建阶段 `npm ci` 失败（常伴随 `Exit handler never called!`）。

### 3.3 .dockerignore（强烈建议）

```text
.git
node_modules
.DS_Store
*.log
dist
```

---

## 4. 其它模板（当前不启用）

> 仅作为参考：如果未来你们放开“纯前端静态站/Java 服务”等类型，再启用对应模板即可。

### 4.1 模板：前端（Vite/React/Vue/静态站）

特点：构建静态资源，运行时用 Nginx 提供服务，端口固定 `8080`。

`Dockerfile`：

```dockerfile
FROM node:20-bookworm-slim AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.25-alpine
COPY --from=build /app/dist /usr/share/nginx/html

# Nginx 默认 80，这里统一映射到 8080（容器内监听 8080）
RUN sed -i 's/listen       80;/listen       8080;/' /etc/nginx/conf.d/default.conf
EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
```

`.dockerignore`（建议）：

```text
.git
node_modules
dist
.DS_Store
*.log
```

### 4.2 模板：Node 服务（Express/Nest/Koa）

特点：容器内进程直接监听 `8080`；支持 `PORT` 但默认 8080。

`Dockerfile`：

```dockerfile
FROM node:20-bookworm-slim AS deps
WORKDIR /app
COPY package*.json ./
RUN npm ci

FROM node:20-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8080

COPY --from=deps /app/node_modules /app/node_modules
COPY . .

EXPOSE 8080
CMD ["npm", "start"]
```

应用侧要求：`npm start` 启动后监听 `process.env.PORT || 8080`。

### 4.3 模板：Java Spring Boot

特点：多阶段构建 jar，运行时只需要 JRE；监听 `8080`。

`Dockerfile`：

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

---

## 5. Runner 构建约定（建议）

Runner 默认构建参数（第一阶段建议固定）：

- `docker build -t <image> -f ./Dockerfile .`
- 镜像 tag：`<acr>/<namespace>/apps/app-<appId>:<commitSha>`

> 后续如需 monorepo 或子目录 Dockerfile，可在 payload 增加 `dockerfilePath` / `buildContext`。


