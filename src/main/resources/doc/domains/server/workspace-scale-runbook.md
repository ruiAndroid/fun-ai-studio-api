# Workspace 扩容一页式运行手册（新增 1 节点）

> 目标：在不引入 K8s 的前提下，把 workspace-node 从 1 台扩展到 2 台，并启用心跳注册与动态路由。

## 执行清单（按顺序）

1) **数据库确认（API 侧）**
   - 确认 `fun_ai_workspace_node`、`fun_ai_workspace_placement` 已存在
   - 如需补索引/默认值：执行运维提供的 ALTER 脚本

2) **新节点基础环境（新服务器）**
   - 安装：JDK17、Maven、Nginx、Podman、podman-docker
   - 目录：`/data/funai/workspaces`、`/data/funai/verdaccio/{conf,storage}`、`/opt/fun-ai-studio/config`
   - 服务：`systemctl enable --now nginx`、`systemctl enable --now podman.socket`

3) **部署 workspace-node（新服务器）**
   - `app.jar` 与 `application-prod.properties` 放到 `/opt/fun-ai-studio`
   - systemd 启动 `fun-ai-studio-workspace`
   - 配置 `workspace-node.internal.allowed-source-ip/shared-secret/require-signature`

4) **节点 Nginx（新服务器）**
   - 配置 `/preview/{appId}` + `auth_request` → `/_ws_port`
   - 反代到 `127.0.0.1:$ws_port`
   - 保留 `/__ws_sleeping` 兜底页与 WebSocket 透传

5) **心跳注册（API + 新节点）**
   - API：`funai.workspace-node-registry.enabled=true` 等参数已配置
   - 新节点：`funai.workspace-node-registry-client.*` 指向 API

6) **入口 Nginx 动态路由（API 入口）**
   - `/preview/{appId}` 子请求 API 获取 `X-WS-Node`
   - 根路径资源按 `ws_appid` cookie 同路由

7) **安全组/防火墙**
   - API → 新节点：放行 `80/7001`
   - 新节点 → API：放行 `8080`（心跳）
   - 新节点 → 103：放行 `4873`（Verdaccio，如需）

## 验收步骤（最小闭环）

1) **心跳注册**
   - 在 API 管理页查看节点列表，确认新节点 `HEALTHY`

2) **路由验证（预览）**
   - 访问 `/preview/{appId}/`，确认可打开
   - 多次访问同一 userId 应固定落点

3) **容器验证**
   - 新节点上 `docker ps` 能看到 `ws-u-{userId}`
   - `run/status` 能返回 RUNNING

4) **npm 安装验证**
   - 在容器内 `npm install` 能走 Verdaccio（如启用）

## 回滚（最短路径）

- 入口 Nginx 暂时恢复单节点转发
- API 侧将新节点 `enabled=0`（或停止心跳上报）
- 需要时仅保留旧节点提供服务

