# Workspace：npm 缓存（最简方案）

## 背景与目标

在线编辑器导入的 Node 项目通常依赖较多。如果每次新项目都在容器内全量 `npm install`：

- 会很慢（重复下载）
- 容器出网不稳定时可能直接失败
- 多人并发时会造成 CPU/IO 峰值

本方案先用**最小改动**跑通流程：在宿主机提供一个**全局 npm cache 目录**，挂载到每个用户容器，让 npm 安装优先命中缓存。

> 说明：这是“跨用户共享”的全局缓存，属于试验性/过渡方案；后续推荐升级为 Verdaccio/Nexus/Artifactory 等企业级私有仓库（代理 + 缓存 + 审计）。

## 方案概述

- 宿主机准备目录：`/data/funai/cache/npm`
- workspace 创建用户容器时：
  - bind mount：`/data/funai/cache/npm` -> 容器 `/opt/funai/npm-cache`（可配置）
  - 注入 npm 配置环境变量：`NPM_CONFIG_CACHE`、`npm_config_prefer_offline`、`npm_config_fetch_*`
- 运行态启动 `npm install` 时：
  - 优先使用该 cache（命中则不出网/少出网）
  - 失败时按重试/超时策略兜底

## 配置项（Spring Boot）

在 `application-prod.properties` 配置：

```properties
funai.workspace.npmCache.enabled=true
funai.workspace.npmCache.hostDir=/data/funai/cache/npm
funai.workspace.npmCache.containerDir=/opt/funai/npm-cache
funai.workspace.npmCache.preferOffline=true
funai.workspace.npmCache.fetchRetries=5
funai.workspace.npmCache.fetchTimeoutMs=120000
```

## 目录准备（宿主机）

```bash
mkdir -p /data/funai/cache/npm
```

## 容器内实际生效的关键点

当 `npmCache.enabled=true` 时，用户容器启动参数会带上：

- `-v /data/funai/cache/npm:/opt/funai/npm-cache`
- `-e NPM_CONFIG_CACHE=/opt/funai/npm-cache`
- `-e npm_config_cache=/opt/funai/npm-cache`
- `-e npm_config_prefer_offline=true`
- `-e npm_config_fetch_retries=5`
- `-e npm_config_fetch_timeout=120000`

运行态脚本在执行 `npm install` 前会输出当前 cache 路径到 `dev.log`，便于排查。

## 缓存预热（建议）

为了减少首次用户安装的抖动，你可以在服务器上**预热**一批固定依赖（例如你们“定死”的依赖集合）。

思路（任选其一）：

- 方式 A：准备一个“预热项目”（只有 `package.json` + lockfile），在任意用户容器里跑一次 `npm ci`/`npm install`，即可把依赖下载进全局 cache。
- 方式 B：在一个临时容器里挂载 cache 目录，直接执行 `npm` 安装（同样会填充 cache）。

## 注意事项

- **一致性**：cache 只是加速，不替代 `node_modules`；项目依赖仍以 lockfile 为准。
- **磁盘占用**：全局 cache 会持续增长；建议配合磁盘监控与定期清理策略（按时间/大小）。
- **安全**：全局 cache 跨用户共享，适合“先跑通”。若你们对供应链安全要求更高，后续应上私有仓库并做包白名单/审计。


固定的依赖配置如下：
{                                                                                                                                                                               "name": "npm-warmup",
  "private": true,
  "version": "1.0.0",
  "dependencies": {
    "@radix-ui/react-dialog": "1.0.5",
    "@radix-ui/react-dropdown-menu": "2.0.6",
    "@radix-ui/react-label": "2.0.2",
    "@radix-ui/react-separator": "1.0.3",
    "@radix-ui/react-slot": "1.0.2",
    "@radix-ui/react-tabs": "1.0.4",
    "@tailwindcss/postcss": "4.1.18",
    "@tailwindcss/vite": "4.0.10",
    "autoprefixer": "10.4.23",
    "axios": "1.13.2",
    "bcryptjs": "3.0.3",
    "class-variance-authority": "0.7.1",
    "clsx": "2.1.1",
    "cors": "2.8.5",
    "date-fns": "2.30.0",
    "dotenv": "16.3.1",
    "express": "4.18.2",
    "jsonwebtoken": "9.0.3",
    "jwt-decode": "4.0.0",
    "lucide-react": "0.475.0",
    "mongoose": "7.8.8",
    "postcss": "8.5.6",
    "prettier": "3.3.3",
    "react": "18.2.0",
    "react-dom": "18.2.0",
    "react-router-dom": "6.22.3",
    "tailwind-merge": "3.4.0",
    "tailwindcss": "4.1.18",
    "typescript": "5.7.2",
    "vite": "6.4.1"
  }
}