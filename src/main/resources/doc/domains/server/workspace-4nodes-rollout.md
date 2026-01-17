# Workspace 4 节点扩容落地清单（粘性落点 + 入口Nginx动态路由）

本文档用于把现有“API + 单台 workspace-node”扩展到 **4 台 workspace-node**，每台都是 workspace+Docker 开发环境。

## 1. 数据库准备（API 服务器）

执行 SQL：`doc/sql/fun_ai_workspace_node_placement.sql`

- `fun_ai_workspace_node`：维护节点清单（nginx_base_url / api_base_url）
- `fun_ai_workspace_placement`：维护 userId -> nodeId 粘性落点

建议先插入 4 台节点记录（用内网 IP/内网域名），避免公网绕一圈。

## 2. 4 台 workspace-node 节点准备（每台都一样）

### 2.1 端口与安全组建议
- 对外（公网入口机 -> 节点）：
  - **80/tcp**：只允许入口 Nginx 服务器来源访问（用于 `/ws/*` 预览转发）
  - **7001/tcp**：只允许 API 服务器来源访问（用于 `/api/fun-ai/workspace/**` 签名代理）
- 节点不应对公网直接暴露用户容器 hostPort（20000+）

### 2.2 节点服务
- `fun-ai-studio-workspace`（workspace-node，默认 7001）
- 节点 Nginx（负责 `/ws/{userId}` -> 本机容器 hostPort）
- Docker/Podman（建议统一版本）
- 可选：Verdaccio（同机缓存 npm）

### 2.3 节点配置关键项（workspace-node）
workspace-node（节点）需要配置 internal auth（只信任 API/入口机）：

- `workspace-node.internal.allowed-source-ip=<API公网或内网IP>`
- `workspace-node.internal.shared-secret=<强随机>`
- `workspace-node.internal.require-signature=true`（API 已经会对 `/api/fun-ai/workspace/**` 签名代理；建议生产开启）

> `/api/fun-ai/workspace/internal/nginx/port` 用的是 `X-WS-Token`（nginxAuthToken），与上述签名是两条链路。

### 2.4 节点 Nginx（/ws 反代）
沿用你们现有双机方案：节点 Nginx `auth_request` 到本机 `7001/api/fun-ai/workspace/internal/nginx/port` 获取 `X-WS-Port`，再反代到 `127.0.0.1:$ws_port`。

参考：`src/main/resources/doc/domains/server/workspace-node.md`

## 3. API 服务器改造点（已在代码实现）
- 新增内部接口：`GET /api/fun-ai/workspace/internal/gateway/node?userId=...`
  - 返回 Header：`X-WS-Node: <nginx_base_url>`
- `/api/fun-ai/workspace/**` 应用层代理：按 `userId` 动态选择节点的 `api_base_url` 转发

## 4. 入口 Nginx（统一公网入口）配置

使用示例配置：
- `src/main/resources/doc/domains/server/small-nginx-workspace-4nodes.conf.example`

核心逻辑：
- `/ws/{userId}`：提取 userId -> `auth_request` 到 API 拿 `X-WS-Node` -> `proxy_pass $ws_node`
- `/@vite/*` 等根路径资源：用 cookie `ws_uid` 做同样的动态路由

## 5. 灰度与回滚
- 灰度：在 `fun_ai_workspace_node.enabled` 控制节点是否参与调度（置 0 即摘除）
- 回滚（最快）：入口 Nginx 改回固定转发到单台节点；API `workspace-node-proxy.enabled=false` 或临时改回单 base-url

## 6. 自检（最少闭环）
- 插入 4 台 node 记录后，访问不同 userId：
  - 首次访问 `/ws/{userId}/` 能被路由到某台节点（查看入口 Nginx 日志/响应头）
  - 同一 userId 多次访问应固定同一 node（粘性）
  - `/api/fun-ai/workspace/run/status?userId=...` 应命中同一 node（看响应头 `X-WS-Upstream`）


