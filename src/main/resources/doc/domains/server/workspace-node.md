# Workspace 容器节点（Workspace 开发服务器（大机）workspace-node）部署与联调说明

本文档描述“**单机 → 双机**”后的 **Workspace 开发服务器（大机）容器节点**（workspace-node）的落地方案与自检方法，目标是：**容器/运行态/依赖缓存等重负载能力上 Workspace 开发服务器（大机）**，API 服务器（小机）保留业务 API + MySQL，并尽量减少改动、快速上线。

## 1. 角色划分（双机）

- **API 服务器（小机，轻量业务）**
  - 对外入口：Nginx（公网/域名入口）
  - 主业务：`fun-ai-studio-api`（Spring Boot）
  - 数据库：MySQL
- **Workspace 开发服务器（大机，容器节点/重负载）**
  - 容器节点服务：`fun-ai-studio-workspace`（workspace-node，Spring Boot，`7001`）
  - 反向代理：Nginx（处理 `/ws/{userId}/...`）
  - 容器运行时：Podman（兼容 docker CLI）
  - Workspace 容器：每用户一个 `ws-u-{userId}`（端口池映射）
  - npm 缓存：Verdaccio（容器，`4873`）
  - Workspace 数据落盘：`/data/funai/...`

> 两台服务器位于不同 VPC 时，优先用公网互通 + 安全组收敛（只放行必要端口与来源 IP）。

## 2. Workspace 开发服务器（大机）关键目录与端口约定

### 2.1 目录

- **workspace 宿主机数据根目录**：`/data/funai/workspaces/{userId}/...`
  - apps：`/data/funai/workspaces/{userId}/apps/{appId}`
  - run：`/data/funai/workspaces/{userId}/run`
  - meta：`/data/funai/workspaces/{userId}/workspace-meta.json`（记录 hostPort 等）
- **verdaccio**
  - 配置：`/data/funai/verdaccio/conf`
  - 存储：`/data/funai/verdaccio/storage`
- **workspace-node 部署（示例约定）**
  - jar：`/opt/fun-ai-studio/app.jar`
  - config：`/opt/fun-ai-studio/config/application-prod.properties`

### 2.2 端口

- **workspace-node**：`7001`（仅供本机 Nginx/本机调用；若跨机调用需收敛来源 IP）
- **Workspace 开发服务器（大机）Nginx**：`80`（供 API 服务器（小机）`/ws/*` 转发；建议只允 API 服务器（小机）来源 IP）
- **Verdaccio**：`4873`（容器网络内使用为主；如需跨机/公网使用需严格收敛）
- **每用户 workspace 预览 hostPort**：例如 `20021`（仅本机 Nginx 反代到 `127.0.0.1:${hostPort}`，不对公网直接暴露）

## 3. Workspace 开发服务器（大机）Nginx（/ws 预览反代）核心逻辑

设计目标：外部统一访问 `/ws/{userId}/...`，Nginx 先询问 workspace-node 得到该 userId 的 `hostPort`，再反代到 `127.0.0.1:${hostPort}`。

### 3.1 `auth_request` 获取端口

- Nginx 子请求：`/_ws_port` → 本机 `127.0.0.1:7001/api/fun-ai/workspace/internal/nginx/port?userId=$ws_uid`
- workspace-node 返回：Header 中携带 `X-WS-HostPort: <port>`
- Nginx 读取 header 设置变量：`$ws_port`

### 3.2 反代到用户容器 hostPort

`/ws/{userId}/...` → `proxy_pass http://127.0.0.1:$ws_port;`

### 3.3 “sleeping 页面”机制（很重要）

当容器已存在但 **dev server 没有在 $ws_port 上监听**时，`127.0.0.1:$ws_port` 会 `connection refused`。为提升 UX，Nginx 常配置：

- upstream 错误 → `error_page ... =200 /__ws_sleeping;`
- 这会导致 `curl -I /ws/...` 看到 **200**，但 body 是一段“已休眠提示页”（可自定义为更友好的 HTML）。

建议：把 `/__ws_sleeping` 做成 **更友好的中文 HTML**（带恢复指引 + 自动刷新），参考 `src/main/resources/doc/阿里云部署文档.md` 中的 Nginx 配置片段。

> 判断是否真正跑起来：以 `run/status` 的 `state=RUNNING` + `portListenPid` 为准。

### 3.4 iframe 跨站（第三方 Cookie）注意事项

如果你把预览页（例如 `https://<preview-host>/ws/<uid>/`）放进控制台页面的 `iframe`，那么对浏览器来说：

- 预览域名的 cookie（例如我们用于根路径资源路由的 `ws_uid`）会变成**第三方 Cookie**
- `SameSite=Lax/Strict` 默认不会在 iframe 场景发送，导致 `/@vite/client`、`/src/*`、以及“根路径 /api 分流到 workspace”的逻辑失效

要让 cookie 在跨站 iframe 中可用，必须：

- **HTTPS**（否则 `Secure` cookie 不生效）
- `Set-Cookie` 增加 `SameSite=None; Secure`

对应 Nginx 配置参考 `src/main/resources/doc/阿里云部署文档.md` 中 `/ws/{userId}/...` location 的 `Set-Cookie ws_uid=...` 段落。

补充：如果你发现访问 `/ws/{userId}/` 在浏览器里出现 **302 循环**（`Location` 还是 `/ws/{userId}/`），一般是因为上游 dev server 需要保留 `/ws/{userId}` 前缀，
此时 workspace-dev Nginx 不要做“剥离前缀再转发”，而应直接：

- `/ws/{userId}/...`：剥离前缀后转发到上游根路径：
  - `location ~ ^/ws/(?<uid>\d+)(?<rest>/.*)?$ { ... proxy_pass http://127.0.0.1:$ws_port$rest; }`
- 根路径资源（通过 cookie 路由到 workspace）：`proxy_pass http://127.0.0.1:$ws_port$request_uri;`

## 3.5 API 服务器（小机）Nginx 只切 `/ws/*` 到 Workspace 开发服务器（大机）（推荐先做，低风险）

目标：先把“预览流量”（`/ws/*`）从 API 服务器（小机）转发到 Workspace 开发服务器（大机）Nginx，**不改动 API 服务器（小机）业务 API**，验证用户预览已经走 Workspace 开发服务器（大机）容器节点。

### 3.5.1 安全组最小放行建议

- Workspace 开发服务器（大机）入方向：
  - **80/tcp**：只允许 API 服务器（小机）公网 IP（例如 `47.118.27.59`）访问（用于 `/ws/*` 转发）
  - （可选）**7001/tcp**：只允许 Workspace 开发服务器（大机）本机或 API 服务器（小机）访问（如果你未来要让 API 服务器（小机）直连 workspace-node API；不做 API 切流时可不开放）

### 3.5.2 API 服务器（小机）Nginx 示例（`/ws/*` → Workspace 开发服务器（大机）Nginx）

以下配置片段放在 API 服务器（小机）对外 server 块中（`server { ... }`），将 `/ws/` 前缀原样转发到 Workspace 开发服务器（大机）：

```nginx
# API 服务器（小机）：把 /ws/ 转发到 Workspace 开发服务器（大机）Nginx

# 重要：/doc 文档与 doc-mermaid.js 必须走 API（否则会出现浏览器 GET /doc-mermaid.js 502，导致 sequenceDiagram 不渲染）
# 若你的 Nginx 有正则静态规则（例如 location ~* \.(js|css|ico)$），请优先加下面的精确/前缀匹配并放在 regex 之前：
# location = /doc-mermaid.js { proxy_pass http://127.0.0.1:8080; }
# location = /favicon.ico    { proxy_pass http://127.0.0.1:8080; }
# location ^~ /doc/          { proxy_pass http://127.0.0.1:8080; }

location ^~ /ws/ {
    proxy_http_version 1.1;

    # WebSocket（在线终端）需要
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;

    # 透传必要头（用于日志、真实来源）
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # 长连接/大响应建议
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_buffering off;

    # 指向 Workspace 开发服务器（大机）Nginx（示例 IP：39.97.61.139）
    proxy_pass http://39.97.61.139;
}
```

说明：

- `proxy_pass http://39.97.61.139;` 不带路径，可让 `/ws/...` 原样落到 Workspace 开发服务器（大机）Nginx 的 `/ws/...`。
- `$connection_upgrade` 需要在 `http {}` 里定义（若你们已有可跳过）：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}
```

### 3.5.3 API 服务器（小机）验证

在 API 服务器（小机）或任意能访问公网入口的机器验证：

```bash
curl -I http://47.118.27.59/ws/<userId>/
```

预期：

- workspace 未 RUNNING 时，可能返回 `200` 且 body 为 `workspace sleeping`（由 Workspace 开发服务器（大机）Nginx 兜底页返回）。
- workspace RUNNING 时，应返回 200/302/200（取决于 dev server），并能在浏览器打开页面。

## 4. workspace-node（7001）内部鉴权（只信任 API 服务器（小机））

Workspace 开发服务器（大机）的 workspace-node 不依赖 MySQL 进行 app 归属校验，默认信任 API 服务器（小机）已完成鉴权/鉴权后的转发。

### 4.1 配置项（注意：必须 kebab-case 小写）

Spring Boot 3 要求属性名是 **kebab-case 全小写**，因此使用：

```properties
workspace-node.internal.allowed-source-ip=47.118.27.59
workspace-node.internal.shared-secret=CHANGE_ME_STRONG_SECRET
workspace-node.internal.require-signature=false
```

说明：

- `allowed-source-ip`：API 服务器（小机）公网 IP（只放行 API 服务器（小机）来源）
- `shared-secret`：后续启用签名校验时用于 HMAC；建议一开始就填强随机值
- `require-signature`：先 `false` 快速跑通；API 服务器（小机）加签后再切 `true`

### 4.2 常见启动错误：配置 key 不合法

若使用 `workspaceNode.internal.*`（含大写 N），启动会报：

- `Configuration property name 'workspaceNode.internal' is not valid: Invalid characters: 'N'`

解决：改为 `workspace-node.internal.*`（见上文）。

## 4.3 节点注册表（心跳）与多节点扩容（推荐）

目标：当有多台 workspace-node 时，API 能基于 **userId -> nodeId** 的粘性落点稳定路由，并能过滤“不健康节点”（心跳过期）。

### 4.3.1 API 侧：心跳接口与鉴权

接口（内部）：

- `POST /api/fun-ai/admin/workspace-nodes/heartbeat`
- Header：`X-WS-Node-Token: <token>`
- body（示例）：
  - `nodeName`：节点名（唯一，例如 ws-node-01）
  - `nginxBaseUrl`：该节点 Nginx 基址（供 /ws 路由）
  - `apiBaseUrl`：该节点 workspace-node API 基址（供 API 侧转发/签名）

配置（API 服务 `application-prod.properties`）：

- `funai.workspace-node-registry.enabled=true`
- `funai.workspace-node-registry.shared-secret=4f2b1a9c8d3e7a60b1c9d7e5f3a8b6c4d2e0f9a7c5b3d1e8f6a4c2e9b7d5f0a1c3e8b2d6f9a0c4e7b1d5f8a2c6e9b3d7f0a4c8e1b5d9f2a6c0e3b7d1f4a8c2e5b9d0f3a7c1e4b8d2f5a9c3e6b0d4f7a1c5e8b2d6f9a0c4e7b1d5f8a2c6e9b3d7f0a4c8e1b5d9f2a6c0e3b7d1f4a8c2`
- `funai.workspace-node-registry.allowed-ips=172.21.138.87`（可选）
- `funai.workspace-node-registry.heartbeat-stale-seconds=60`

说明：

- 心跳接口位于 `/api/fun-ai/admin/**` 下，但**不使用** `X-Admin-Token`，而是使用独立的 `X-WS-Node-Token`（更符合职责分离）。

### 4.3.2 workspace-node 侧：心跳上报

配置（workspace 服务 `application-prod.properties`）：

- `funai.workspace-node-registry-client.enabled=true`
- `funai.workspace-node-registry-client.api-base-url=http://<API_HOST>:8080`
- `funai.workspace-node-registry-client.node-name=ws-node-01`
- `funai.workspace-node-registry-client.nginx-base-url=http://<THIS_NODE_NGINX>`
- `funai.workspace-node-registry-client.node-api-base-url=http://<THIS_NODE>:7001`
- `funai.workspace-node-registry-client.token=CHANGE_ME_STRONG_SECRET`
- `funai.workspace-node-registry-client.interval-seconds=15`

### 4.3.3 路由与健康：manual-drain + 条件自动迁移（默认关闭）

- **默认策略（manual-drain）**：\n  当 userId 已绑定的节点心跳过期时，API 侧会提示 “node unhealthy，请人工迁移”，不会自动改写 placement。\n
- **条件自动迁移（guarded auto-reassign，默认关闭）**：\n  仅当满足安全条件（例如 placement.last_active_at 距今超过阈值）且开关打开时，才允许自动重分配。\n
相关配置（API 服务侧）：\n
- `workspace-node.failover.auto-reassign.enabled=false`\n
- `workspace-node.failover.auto-reassign.max-idle-minutes=30`\n

### 4.3.4 运维：placements / reassign / drain

API 管理接口（需要 `X-Admin-Token`）：\n
- `GET /api/fun-ai/admin/workspace-nodes/placements?nodeId=...&offset=0&limit=200`\n
- `POST /api/fun-ai/admin/workspace-nodes/reassign`（body：`userId`、`targetNodeId`）\n
- `POST /api/fun-ai/admin/workspace-nodes/drain`（body：`sourceNodeId`、`targetNodeId`、`limit`）\n

说明：

- 这些接口只改 “userId -> nodeId” 的路由落点；是否触发容器重建/数据迁移需要后续能力配合。

## 5. Workspace 开发服务器（大机）基础环境要求（Alibaba Cloud Linux 3）

### 5.1 容器运行时（Podman）

Alibaba Cloud Linux 3 默认是 Podman，通常安装了 `podman-docker` 后可以直接用 `docker` 命令（会提示 Emulate Docker CLI）。

建议确保 socket 启动：

```bash
systemctl enable --now podman.socket
```

### 5.1.1 Harbor（HTTP）拉镜像必配 insecure registry（常见 443 refused）

如果你把 workspace 基础镜像从 ACR 迁移到自建 Harbor（例如 `172.21.138.103/...`），且 Harbor 起步只启用 **HTTP(80)**，
那么 podman 默认会尝试走 **HTTPS**，出现典型错误：

```text
pinging container registry 172.21.138.103: Get "https://172.21.138.103/v2/": dial tcp 172.21.138.103:443: connect: connection refused
```

解决：在 workspace-node 机器上配置 `/etc/containers/registries.conf` 为 insecure：

```toml
[[registry]]
location = "172.21.138.103"
insecure = true
```

验证：

```bash
podman pull 172.21.138.103/<project>/<image>:<tag>
```

### 5.2 构建工具版本要求

workspace-node 采用 Java 17 编译（`--release 17`），需要：

- **JDK 17（含 javac）**：`java-17-openjdk-devel`
- **Maven >= 3.6.3**（本项目使用的插件要求）

常见错误与修复：

- Maven 太老：
  - 报 `maven-clean-plugin ... requires Maven version 3.6.3`
  - 解决：升级 Maven（例如 3.9.6）
- javac 不是 17：
  - 报 `release version 17 not supported`
  - 解决：安装 `java-17-openjdk-devel` 并确保 `javac -version` 为 17（必要时配置 alternatives）

## 6. workspace-node 部署（systemd）

### 6.1 打包

```bash
cd /opt/fun-ai-studio/fun-ai-studio-workspace
mvn -DskipTests clean package
cp -f target/*SNAPSHOT.jar /opt/fun-ai-studio/app.jar
```

### 6.2 systemd 单元（示例）

`/etc/systemd/system/fun-ai-studio-workspace.service`

```ini
[Unit]
Description=fun-ai-studio workspace-node (7001)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/fun-ai-studio
Environment="JAVA_OPTS=-Xms256m -Xmx512m"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/fun-ai-studio/app.jar --spring.profiles.active=prod --spring.config.location=/opt/fun-ai-studio/config/
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

启动：

```bash
systemctl daemon-reload
systemctl enable --now fun-ai-studio-workspace
systemctl status fun-ai-studio-workspace --no-pager -l
```

## 7. Verdaccio（npm 代理缓存）部署与踩坑记录

### 7.1 典型容器启动方式

```bash
docker run -d --name verdaccio --restart=always \
  --network funai-net \
  -v /data/funai/verdaccio/conf:/verdaccio/conf:Z \
  -v /data/funai/verdaccio/storage:/verdaccio/storage:Z \
  <你的verdaccio镜像>
```

### 7.2 典型踩坑：npm 访问 verdaccio 报 500（EACCES）

现象：

- workspace 容器内执行 `npm create vite@latest ...` 或 `npm install` 报：
  - `npm error 500 Internal Server Error - GET http://verdaccio:4873/<pkg>`
- verdaccio 容器日志出现：
  - `EACCES: permission denied, mkdir '/verdaccio/storage/<pkg>'`

根因：

- 挂载到容器的 `/verdaccio/storage` 在宿主机上权限不正确，verdaccio 进程用户无法写入，导致无法落盘缓存，最终返回 500。

解决（示例）：

```bash
docker rm -f verdaccio 2>/dev/null || true
chown -R 10001:65533 /data/funai/verdaccio/conf /data/funai/verdaccio/storage
chmod -R u+rwX,g+rwX /data/funai/verdaccio/conf /data/funai/verdaccio/storage
```

验证：

```bash
docker exec ws-u-<userId> sh -lc "npm config set registry http://verdaccio:4873 && npm view create-vite version"
```

## 8. 自检清单（Workspace 开发服务器（大机）闭环）

### 8.1 服务与端口

```bash
ss -lntp | grep 7001
```

### 8.2 确保用户容器存在（ensure）

```bash
curl -s -X POST "http://127.0.0.1:7001/api/fun-ai/workspace/container/ensure?userId=<uid>"
cat /data/funai/workspaces/<uid>/workspace-meta.json
```

### 8.3 初始化 app（示例：create-vite）并启动 run

当 app 目录没有 `package.json` 时，可在容器内临时初始化（仅用于验证链路）：

```bash
docker exec ws-u-<uid> sh -lc "cd /workspace/apps/<appId> && rm -rf * && npm config set registry http://verdaccio:4873 && npm create vite@latest . -- --template vanilla && npm install"
curl -s -X POST "http://127.0.0.1:7001/api/fun-ai/workspace/run/start?userId=<uid>&appId=<appId>"
curl -s "http://127.0.0.1:7001/api/fun-ai/workspace/run/status?userId=<uid>"
```

### 8.4 预览验证

```bash
curl -I http://127.0.0.1:<hostPort>/
curl -I "http://127.0.0.1/ws/<uid>/" -H "Host: <BIG_PUBLIC_IP>"
```

## 9. 清理验证数据（避免污染真实 userId/appId）

如果验证时使用了真实 `userId/appId` 并在容器内初始化了临时项目，建议清理：

1) 停止当前 run：

```bash
curl -s -X POST "http://127.0.0.1:7001/api/fun-ai/workspace/run/stop?userId=<uid>"
```

2) 删除测试 app 目录（只删这个 appId）：

```bash
rm -rf /data/funai/workspaces/<uid>/apps/<appId>
```

3) 可选：删除用户容器（下次 ensure 会重建；数据目录仍在宿主机）：

```bash
curl -s -X POST "http://127.0.0.1:7001/api/fun-ai/workspace/container/remove?userId=<uid>"
```

## 10. 运维：定位并清理“单用户容器磁盘暴涨（npm 缓存）”

现象：某个 `ws-u-{userId}` 容器占用磁盘达到数 GB/十几 GB，且用户删除项目后占用不下降。

根因（常见）：`npm install` 默认写 `~/.npm`（`_cacache/_npx`），这是容器可写层的一部分，不会随 `apps/{appId}` 的删除而自然回收。

### 10.1 宿主机快速定位“哪个容器占用大”

（Podman/Docker 差异较大，以下命令择一使用）

```bash
# 1) 看容器总体（镜像+可写层）占用（podman）
podman ps -a --size

# 2) 看 docker 存储占用（docker/podman-docker）
docker system df
```

### 10.2 定位某个用户容器的 npm 缓存占用

```bash
uid=10000021
name="ws-u-$uid"

docker exec "$name" bash -lc 'du -sh ~/.npm 2>/dev/null || true; du -sh ~/.npm/_cacache ~/.npm/_npx 2>/dev/null || true'
```

### 10.3 安全清理（推荐：只删 npm cache，不动项目代码）

```bash
uid=10000021
name="ws-u-$uid"

# 清理 npm 的大头缓存
docker exec "$name" bash -lc 'rm -rf ~/.npm/_cacache ~/.npm/_npx 2>/dev/null || true'
docker exec "$name" bash -lc 'du -sh ~/.npm 2>/dev/null || true'
```

### 10.4 更彻底的回收：删除用户容器（不会删宿主机 workspaces 代码目录）

如果容器可写层已经膨胀严重（且你接受下次访问会重建容器），可直接删除容器释放可写层：

```bash
curl -s -X POST "http://127.0.0.1:7001/api/fun-ai/workspace/container/remove?userId=10000021"
```

### 10.5 长期治理（推荐）

平台侧已支持把 npm cache 从 `~/.npm` 迁移到可控目录并限额：

- `funai.workspace.npmCacheMode=APP`（缓存落到 `{APP_DIR}/.npm-cache`，删项目即可回收）
- `funai.workspace.npmCacheMaxMb=2048`（超过阈值自动清理）


