# 多机扩容方案（面向 Deploy / Runner / Runtime）

本文档面向“发布能力”的服务器落地：当用户在 Workspace 容器内完成前后端一体项目开发后，平台需要把用户应用部署到 Runtime 节点（容器）并对公网统一域名暴露。

> Workspace 多机方案请看：`doc/domains/server/scaling-workspace.md`  
> Deploy/Runner/Runtime 的逻辑架构与互联矩阵请看：`doc/domains/deploy/architecture.md`

---

## 1. 推荐服务器划分（现网 5 台：其中 Deploy/Runner/Runtime 为 3 台）

- **API 服务器（ControlPlane 入口）**
  - 部署：`fun-ai-studio-api`（Spring Boot）
  - 作用：用户统一入口、鉴权、业务编排；内部调用 Deploy 控制面创建 Job
  - 备注：对公网通常只通过网关暴露 80/443；API 自身端口建议只对网关开放

- **Deploy 服务器（发布控制面）**
  - 部署：`fun-ai-studio-deploy`（Spring Boot）
  - 作用：控制面管理 Job / runtime 节点；对外提供 claim/heartbeat/report 与 admin 运维接口
  - 现网：`172.21.138.100`

- **Runner 服务器（执行面）**
  - 部署：`fun-ai-studio-runner`（Python）
  - 作用：轮询 claim 任务、执行构建/推送镜像（后续）、调用 runtime-agent 部署，回传 report
  - 现网：`172.21.138.101`

- **Runtime 节点（可多台，横向扩容）**
  - 部署：`runtime-agent`（FastAPI）+ Docker/Podman + 网关（Traefik/Nginx）
  - 作用：承载用户应用容器，对外提供 `/apps/{appId}/...` 访问
  - 现网：`172.21.138.102`

---

## 2. 端口与安全组放行建议（按最小暴露）

### 2.1 API 服务器

- 入站（from 网关/SLB）：
  - `80/443`（网关端口，若网关与 API 同机）
  - `8080`（仅网关/内网访问，按你们实际部署）
- 出站：
  - 到 Deploy：`7002`
  - 到 DB/Redis：按实际端口

### 2.2 Deploy 服务器（Deploy + Runner）

- 入站（内网）：
  - `7002`（Deploy：API、Runner、runtime-agent 心跳需要访问）
- 出站：
  - 到 Runtime-Agent：`7005`
  - 到镜像仓库（后续）：`443`

### 2.3 Runtime 节点

- 入站：
  - `80/443`（对公网：统一域名下 `/apps/{appId}`）
  - `7005`（内网：仅允许运行 Runner 的部署机访问）
- 出站：
  - 到 Deploy：`7002`（心跳注册）
  - 到镜像仓库（后续）：`443`

---

## 3. 必备运行环境（建议 checklist）

### 3.1 Deploy 服务器（Runner 执行需要）

- Java 17（运行 `fun-ai-studio-deploy`）
- Docker/Podman（用于构建、镜像操作，后续可接 `buildx`）
- Python 3.10+（Runner）
- （可选）BuildKit/buildx：用于高效 `docker buildx build --push`

### 3.2 Runtime 节点

- Docker/Podman（运行用户应用容器）
- Python 3.10+（runtime-agent）
- Traefik/Nginx（统一域名路径路由 `/apps/{appId}`）

---

## 3.3 Deploy 控制面（fun-ai-studio-deploy）在全新服务器落地（建议照抄）

> 目标：把 `fun-ai-studio-deploy` 跑成一个 systemd 常驻服务（端口 `7002`），并能通过 `/internal/health` 自检。

### 3.3.1 安全组/防火墙（先做，避免“跑起来但连不上”）

- **入站（Deploy 服务器 100）**：
  - `7002/tcp`：仅允许 **API 服务器**、**Runner 所在机（若不同）**、以及 **Runtime 节点**访问（心跳/claim/report 走这个端口）
- **不要**把 `7002` 暴露公网

### 3.3.2 安装 Java 17（两种常见发行版命令）

> 你只需要其一：根据服务器 OS 选择对应命令。

**Alibaba Cloud Linux 3 / CentOS / RHEL 系：**

```bash
sudo yum -y install java-17-openjdk java-17-openjdk-devel
java -version
```

**Ubuntu / Debian 系：**

```bash
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk
java -version
```

### 3.3.3 准备部署目录与运行用户

```bash
sudo useradd -r -s /sbin/nologin funai || true
sudo mkdir -p /opt/funai/deploy /opt/funai/deploy/config /var/log/funai-deploy
sudo chown -R funai:funai /opt/funai/deploy /var/log/funai-deploy
```

### 3.3.4 产出/上传 Jar（推荐：在构建机打包，把 jar 上传到 100）

在你的构建机（开发机/CI）打包：

```bash
cd fun-ai-studio-deploy
mvn -DskipTests clean package
```

把 `target/*.jar` 上传到 100（示例路径）：

- `/opt/funai/deploy/app.jar`

> 备注：不推荐在生产机上装 Maven 全量依赖来构建（能跑就行，构建尽量交给 CI）。

### 3.3.5 配置文件（生产建议改 token/secret 与 allowed-ips）

把 Deploy 配置放到：

- `/opt/funai/deploy/config/application-prod.properties`

你至少需要确认（并替换 `CHANGE_ME_*`）：

- `deploy.admin.token`
- `deploy.runtime-node-registry.shared-secret`
- （可选）`deploy.admin.allowed-ips`、`deploy.runtime-node-registry.allowed-ips`

> 参考默认配置：`fun-ai-studio-deploy/src/main/resources/application.properties`

### 3.3.6 systemd 常驻（Deploy 7002）

创建文件：`/etc/systemd/system/fun-ai-studio-deploy.service`

```ini
[Unit]
Description=fun-ai-studio deploy control plane (7002)
After=network.target

[Service]
Type=simple
User=funai
WorkingDirectory=/opt/funai/deploy
Environment="JAVA_OPTS=-Xms256m -Xmx512m"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/funai/deploy/app.jar --spring.profiles.active=prod --spring.config.location=/opt/funai/deploy/config/
Restart=always
RestartSec=3
SuccessExitStatus=143
StandardOutput=append:/var/log/funai-deploy/stdout.log
StandardError=append:/var/log/funai-deploy/stderr.log
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

启动并设置开机自启：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now fun-ai-studio-deploy
sudo systemctl status fun-ai-studio-deploy --no-pager
```

### 3.3.7 健康检查与日志排查

健康检查（本机）：

```bash
curl -sS http://127.0.0.1:7002/internal/health
```

看日志：

```bash
sudo tail -n 200 /var/log/funai-deploy/stdout.log
sudo tail -n 200 /var/log/funai-deploy/stderr.log
```

---

## 4. 最小联调（运维验收：链路通）

目标：验证“心跳注册 → 节点可见 → Job 创建 → Runner 领取 → 调用 runtime-agent → report 回传”。

### 4.1 Deploy 启动参数（关键配置）

Deploy 服务配置（`fun-ai-studio-deploy`）：

- `deploy.admin.token`：Deploy 运维接口 token（`X-Admin-Token`）
- `deploy.runtime-node-registry.shared-secret`：runtime 节点心跳 token（`X-RT-Node-Token`）
- `deploy.runtime-node-registry.allowed-ips`：可选，runtime 节点来源 IP 白名单

### 4.2 Runtime-Agent 启动参数（关键环境变量）

- `RUNTIME_AGENT_TOKEN`：Runner 调用 runtime-agent 时的 token（`X-Runtime-Token`）
- `DEPLOY_BASE_URL`：Deploy 基址（例如 `http://<deploy-host>:7002`）
- `DEPLOY_NODE_TOKEN`：同 `deploy.runtime-node-registry.shared-secret`
- `RUNTIME_NODE_NAME` / `RUNTIME_NODE_AGENT_BASE_URL` / `RUNTIME_NODE_GATEWAY_BASE_URL`

### 4.3 Runner 启动参数（关键环境变量）

- `DEPLOY_BASE_URL`（例如 `http://<deploy-host>:7002`）
- `RUNNER_ID`
- `RUNTIME_AGENT_TOKEN`（与 runtime-agent 一致）

### 4.4 观察点（建议）

- Deploy：`GET /admin/runtime-nodes/list`（确认节点在线）
- API：创建部署 Job（确认能调用 Deploy 创建 PENDING）
- Runner 日志：claim 拿到任务，并打印 `runtimeNode.agentBaseUrl` / `gatewayBaseUrl`
- runtime-agent 日志：收到 `/agent/apps/deploy`，Docker 创建/更新容器成功
- Deploy：Job 状态最终为 `SUCCEEDED/FAILED`

---

## 5. 常见问题（FAQ）

### 5.1 用户点击部署，是访问 API 还是 Deploy？

**只访问 API**。Deploy 属于内部控制面服务，不建议暴露到公网；API 负责鉴权与业务编排，再内部调用 Deploy 创建 Job。

### 5.2 Deploy 服务器需要 Docker 吗？

如果 Runner 与 Deploy 同机，且 Runner 需要构建镜像/执行容器相关命令，那么 **Deploy 服务器需要 Docker/Podman**。  
（未来 Runner 可拆到独立执行节点池，但起步阶段同机最省成本。）


