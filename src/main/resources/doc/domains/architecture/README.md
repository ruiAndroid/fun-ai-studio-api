# 系统架构说明（Fun AI Studio API）

本文档提供“系统级”架构视角：**总架构图** + **各子域子架构图** + 关键链路时序图，帮助快速理解各模块组成与交互方式。

> 文档偏“架构与设计思路”，具体接口/字段细节以各域文档为准（`doc/domains/*`）。

---

## 1. 系统目标与核心体验

- **目标**：给用户提供“应用管理 + 在线开发环境（Workspace）”的一体化能力：打开编辑器、同步文件、运行预览、查看日志、在线终端、可选的容器内 Mongo。
- **体验关键**：前端尽量通过少量 API 即可进入编辑/预览；后端通过受控脚本与状态文件，保证“平台拥有最终控制权”（避免进程/端口/状态错乱）。
- **发布能力（新增）**：用户完成开发后，平台将“用户前后端一体应用”部署到 Runtime 节点（容器）并对公网统一域名下路径暴露（`/apps/{appId}/...`）。

---

## 2. 总架构图（现网 6 台为主，兼容单机/最小环境）

> 说明：你们现网已演进为 **6 台模式（API / workspace-dev / Deploy / Runner / Runtime / Git）**。  
> 本章会先给出现网总架构图，再保留“单机版”用于本地/最小环境。

### 2.1 现网 6 台模式（推荐/当前）

```mermaid
flowchart TD
  subgraph Public["公网/统一入口"]
    Browser["用户浏览器/控制台"]
    Gateway["统一入口网关(Nginx/Traefik 80/443)"]
  end

  subgraph APIPlane["API（入口 / Control Plane）"]
    Api["fun-ai-studio-api (8080)"]
    Mysql[("MySQL(按实际可同机/独立)")]
  end

  subgraph WorkspacePlane["workspace-dev（开发态）"]
    WsNginx["workspace-dev Nginx (80)"]
    WsNode["workspace-node (fun-ai-studio-workspace 7001)"]
    WsUser["workspace 用户容器(ws-u-{userId})"]
    Verdaccio["Verdaccio npm proxy (4873, in-net)"]
    HostFS["宿主机目录(/data/funai/workspaces/{userId})"]
  end

  subgraph GitPlane["源码真相源(Git Plane)"]
    Git["Gitea (103:3000/2222)"]
  end

  subgraph DeployPlane["发布能力（Deploy / Runner / Runtime）"]
    Deploy["fun-ai-studio-deploy (7002)"]
    Runner["fun-ai-studio-runner (Python)"]
    RTGW["Runtime 网关(80/443)"]
    Agent["runtime-agent (7005)"]
    AppCtn["用户应用容器(appId)"]
  end

  Browser --> Gateway
  Gateway -->|"业务 API (/api/fun-ai/**)"| Api
  Gateway -->|"预览入口 (/ws/{userId}/...)"| WsNginx
  Gateway -->|"应用访问 (/apps/{appId}/...)"| RTGW

  Api --> Mysql

  %% workspace-dev
  Api -->|"workspace 接口转发(workspace-node-proxy)"| WsNode
  WsNginx -->|"auth_request 查端口"| WsNode
  WsNginx -->|"反代到 hostPort"| WsUser
  WsUser --> HostFS
  WsUser --> Verdaccio

  %% deploy pipeline
  Api -->|"内部调用(创建/查询 Job)"| Deploy
  Runner -->|"claim/heartbeat/report"| Deploy
  Runner -->|"ssh clone/pull 源码"| Git
  Agent -->|"runtime 节点心跳"| Deploy
  Runner -->|"deploy/stop/status"| Agent
  RTGW --> AppCtn
```

### 关键点（现网）

- **API 是用户唯一入口**：前端只访问 API；预览 `/ws/**` 与运行态 `/apps/**` 都由网关按路径分流。
- **workspace-dev 专注“开发态容器”**：容器/端口池/verdaccio/npm/运行日志等重负载集中在 87。
- **发布能力三件套**：Deploy（控制面）+ Runner（执行面）+ Runtime（运行态）。
- **源码真相源（Git）**：Runner 从 Git(103) 拉取源码进行构建；Workspace(87) 负责开发态编辑与 push。

---

### 2.2 单机版（本地/最小环境）

```mermaid
flowchart TD
  Browser["浏览器(BrowserUI)"] --> Nginx["网关(Nginx_Gateway_80_443)"]
  Browser --> Api["后端API(SpringBoot_FunAiStudioAPI_8080)"]
  Api --> Mysql[("数据库(MySQL)")]

  subgraph host ["单机(SingleHost)"]
    Docker["容器运行时(DockerEngine_or_PodmanDocker)"]
    UserCtn["用户容器(WorkspaceContainers_ws_u_userId)"]
    Verdaccio["npm代理(Verdaccio_NpmProxy_4873_in_net)"]
    HostFS["宿主机目录(HostFS_hostRoot_userId)"]
  end

  Api --> Docker
  UserCtn --> HostFS
  UserCtn --> Verdaccio

  Browser -->|"预览入口(/ws/{userId})"| Nginx
  Nginx -->|"端口查询(auth_request_port)"| Api
  Nginx -->|"转发(proxy_to_127_0_0_1_hostPort)"| UserCtn
```

### 关键点

- **对外入口**通常由 Nginx 统一承接（只开 80/443），API 与预览 `/ws/{userId}/` 都通过反代完成。
- **Workspace 容器**由后端通过 `docker` CLI 管理（`run/exec/inspect/stop`）。
- **npm-cache** 使用 Verdaccio（同机容器网络访问）减少出网、提升 install 稳定性与速度。

---

## 3. 各子域子架构图（按域拆分）

### 3.1 App 域子架构图（应用管理 + open-editor 聚合编排）

```mermaid
flowchart TD
  FE["前端(Frontend)"] --> AppCtl["应用控制器(AppController)"]
  AppCtl --> AppSvc["应用服务(AppService)"]
  AppSvc --> Mysql[("数据库表(MySQL_app_tables)")]

  AppCtl --> WsSvc["工作区服务(WorkspaceService)"]
  WsSvc --> Docker["容器命令(DockerCLI)"]
  WsSvc --> RunState[("运行态记录(WorkspaceRun_last_known)")]
  RunState --> Mysql
```

说明（配合图看）：

- App 域负责 **应用 CRUD** 与 **open-editor 聚合编排**，Workspace 域负责容器/文件/运行态等底层能力。

### 3.2 Workspace 域子架构图（容器/文件/运行态/实时/internal/npm-cache/Mongo）

```mermaid
flowchart TD
  FE["前端(Frontend)"] --> WsContainerCtl["容器接口(WorkspaceContainerAPI)"]
  FE --> WsFilesCtl["文件接口(WorkspaceFilesAPI)"]
  FE --> WsRunCtl["运行态接口(WorkspaceRunAPI)"]
  FE --> WsRealtimeCtl["实时通道(SSE)(WorkspaceRealtimeSSE)"]
  FE --> WsTerminal["在线终端(WS)(WorkspaceTerminalWS)"]

  Nginx["网关(Nginx_Gateway)"] --> WsInternalCtl["内部端口查询(WorkspaceInternalAPI_port)"]

  WsContainerCtl --> WsSvc["核心服务(WorkspaceServiceImpl)"]
  WsFilesCtl --> WsSvc
  WsRunCtl --> WsSvc
  WsRealtimeCtl --> WsSvc
  WsTerminal --> WsSvc
  WsInternalCtl --> WsSvc

  WsSvc --> Docker["容器命令(DockerCLI)"]
  Docker --> Ctn["用户容器(Container_ws_u_userId)"]

  Ctn --> HostFS["宿主机目录(HostRoot_userId_apps_run)"]
  Ctn --> Verdaccio["npm代理(Verdaccio_NpmProxy)"]
  Ctn --> Mongod["数据库(mongod_optional_127_0_0_1)"]

  WsMongoCtl["Mongo浏览器接口(MongoExplorerAPI)"] --> Docker
  WsMongoCtl --> Ctn
```

说明（配合图看）：

- **container 子系统**：ensure/status/heartbeat（保证容器与挂载就绪）
- **files 子系统**：宿主机落盘，容器通过 bind mount 可见
- **run 子系统**：受控任务（dev/preview/build/install）+ `current.json`/日志
- **realtime 子系统**：SSE（状态/日志增量）+ WS 终端（docker exec）
- **internal 子系统**：Nginx `auth_request` 查 `userId -> hostPort`（无副作用）
- **npm-cache**：通过 `funai-net` 访问 Verdaccio
- **Mongo（可选）**：容器内 `mongod`；Mongo Explorer 通过后端 `docker exec mongosh` 只读查询

### 3.3 Server 域子架构图（多机扩容：ControlPlane + WorkspaceNode）

```mermaid
flowchart TD
  Browser["浏览器(BrowserUI)"] --> Gateway["网关(Nginx_or_Gateway)"]
  Gateway --> Api["API(入口_fun-ai-studio-api)"]

  Api --> Mysql[("数据库(MySQL)")]
  Api --> Redis[("缓存/锁(Redis)")]

  Gateway --> WsNginxA["workspace-dev Nginx_A (/ws)"]
  Gateway --> WsNginxB["workspace-dev Nginx_B (/ws)"]

  WsNginxA --> NodeA["workspace-node_A (7001)"]
  WsNginxB --> NodeB["workspace-node_B (7001)"]

  NodeA --> DockerA["容器运行时(DockerEngine_A)"]
  NodeB --> DockerB["容器运行时(DockerEngine_B)"]

  DockerA --> CtnA["用户容器(ws-u-{userId})"]
  DockerB --> CtnB["用户容器(ws-u-{userId})"]

  Api --> Placement[("用户落点表(userId_to_node_hostPort)")]
  Placement --> Mysql
  Placement --> Redis
```

说明（配合图看）：

- 扩容关键是把 **userId 落点（nodeId/hostPort）** 存起来，并让 Gateway 能按 userId 路由到正确的 WorkspaceNode。

### 3.4 Deploy 域子架构图（控制面 / Runner / Runtime）

```mermaid
flowchart TD
  FE["前端/用户(User/Console)"] --> API["API(统一入口_fun-ai-studio-api)"]
  API --> Deploy["Deploy 控制面(fun-ai-studio-deploy)"]
  Runner["Runner(执行面_fun-ai-studio-runner)"] --> Deploy
  Agent["Runtime-Agent(fun-ai-studio-runtime)"] --> Deploy
  Runner --> Agent

  GW["Runtime 网关(Traefik/Nginx)"] --> App["用户应用容器(AppContainer)"]
  FE -->|"访问 /apps/{appId}/..."| GW
```

说明（配合图看）：

- **用户只访问 API**：API 负责鉴权与入口编排，内部调用 Deploy 创建/查询 Job。
- **Deploy 不执行用户代码**：Deploy 只负责任务编排与记录（控制面），执行动作由 Runner 完成（执行面）。
- **Runtime 对外暴露统一入口**：用户应用最终跑在 Runtime 节点的容器里，通过网关统一域名下按路径访问。

深入阅读：

- `doc/domains/deploy/README.md`（API 入口与调用链）
- `doc/domains/deploy/architecture.md`（整体架构与互联矩阵/运维视角）

---

## 4. 关键链路（时序图）

### 4.1 open-editor（前端进入编辑器的一次性编排）

```mermaid
sequenceDiagram
participant FE as "前端(Frontend)"
participant App as "应用接口(AppAPI)"
participant WS as "工作区服务(WorkspaceService)"

FE->>App: open-editor(打开编辑器)(userId,appId)
App->>WS: ensureAppDir(userId,appId)
App->>WS: startDev(userId,appId) (非阻塞)(non-blocking)
App-->>FE: 运行态+目录(runStatus+projectDir)
```

### 4.2 /ws/{userId}/ 预览反代（Nginx auth_request + 端口映射）

```mermaid
sequenceDiagram
participant Browser as "浏览器(Browser)"
participant Nginx as "网关(Nginx)"
participant Api as "内部端口查询(InternalPortAPI)"
participant Ctn as "用户容器(WorkspaceContainer)"

Browser->>Nginx: GET /ws/{userId}/...
Nginx->>Api: auth_request(鉴权/查端口) /workspace/internal/nginx/port?userId
Api-->>Nginx: Header X-WS-Port:{hostPort}(端口)
Nginx->>Ctn: proxy_pass(转发) 127.0.0.1:{hostPort}
Ctn-->>Browser: HTML/JS/CSS/Assets
```

### 4.3 npm-cache（Verdaccio 代理仓库）

```mermaid
flowchart TD
  WsContainer["用户容器(WorkspaceContainer)"] -->|"安装依赖(npm_install)"| Verdaccio["代理仓库(Verdaccio_RegistryProxy)"]
  Verdaccio -->|"未命中(cache_miss)"| Upstream["上游源(UpstreamRegistry_npmmirror)"]
  Verdaccio -->|"命中(cache_hit)"| WsContainer
```

### 4.4 Mongo Explorer（不暴露端口的只读代理）

```mermaid
sequenceDiagram
participant Browser as "浏览器(BrowserUI)"
participant Api as "后端API(FunAiStudioAPI)"
participant Docker as "容器运行时(DockerEngine)"
participant Ws as "用户容器(WorkspaceContainer)"
participant Mongo as "数据库(mongod)"

Browser->>Api: /workspace/mongo/collections?userId&appId
Api->>Docker: docker exec ws-u-{userId} mongosh --eval
Docker->>Ws: exec mongosh
Ws->>Mongo: query(查询)(127.0.0.1:27017/db_appId)
Mongo-->>Ws: result
Ws-->>Docker: JSON
Docker-->>Api: JSON
Api-->>Browser: Result{data}(结果)
```

---

## 5. 文档索引（按域深入）

- `doc/domains/app/README.md`
- `doc/domains/workspace/README.md`
- `doc/domains/deploy/README.md`
- `doc/domains/server/scaling-workspace.md`


