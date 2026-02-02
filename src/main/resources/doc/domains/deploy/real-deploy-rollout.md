# 真实部署闭环落地计划（现网 6 台：API/Workspace/Deploy/Runner/Runtime/Git）

本文档把“从用户点击部署到公网可访问”的全链路拆成可落地步骤，适用于你当前的 6 台现网：

- API：`172.21.138.91`
- Workspace-dev：`172.21.138.87`
- Deploy：`172.21.138.100`
- Runner：`172.21.138.101`
- Runtime：`172.21.138.102`
- Git（Gitea）：`172.21.138.103`

目标：先跑通最小闭环（Deploy→Runner→Runtime），再逐步引入“源码获取/构建/回滚/多 Runtime 扩容”等复杂度。

---

## 1. 三类“存储/状态”必须先统一口径

### 1.1 Workspace（开发态存储：源码/依赖/临时文件）

- **宿主机落盘**：Workspace-dev(87) `/data/funai/workspaces`
- **容器名**：`ws-u-{userId}`（示例）
- **预览入口**：统一公网入口下的 `/preview/...`（由网关转发到 87 的 Nginx）

> 结论：Workspace 是“开发态”，不建议 Runtime/Runner 直接依赖 87 的文件系统做部署交付。

### 1.2 制品（交付物：镜像）

- **推荐唯一制品**：Harbor 镜像（Runner build → push；Runtime pull → run）
- 好处：跨机器交付稳定；避免“复制目录/rsync/zip 传输”带来的不确定性

### 1.3 控制面状态（Deploy 落库到 API 的 MySQL）

Deploy 控制面的主数据落库（MySQL 在 91）：

- `fun_ai_deploy_runtime_node`（Runtime 节点注册表）
- `fun_ai_deploy_runtime_placement`（`appId -> nodeId` 粘性落点）
- `fun_ai_deploy_job`（Job 队列）
- `fun_ai_deploy_app_run`（last-known：最后一次部署/错误/落点）

> 结论：节点管理页面（`/admin/nodes-admin.html`）调用 Deploy 的 admin 接口即可；页面不直连 DB。

### 1.4 走 Git 以后，“用户数据落盘”是否还需要？

需要，但要区分“落盘的是什么”：

- **源码（代码）**：如果 Runner 从 Git 拉代码，那么“源码的长期存储”由 Git 承担，**不需要**你们在 87/100/101/102 再做一份“源码落盘副本”。
  - Runner 只需要一个**临时工作目录**（clone/build 用），job 结束即可清理（可选保留少量缓存加速）。
- **开发态文件**：Workspace 仍然需要落盘（87 的 workspaces），这是给在线开发/依赖缓存/临时文件用的。
- **控制面数据**：Deploy 的 job / placement / app_run 等必须落库（91 的 MySQL），否则页面状态、重启恢复、排障都会受影响。
- **运行态持久数据**：如果用户应用需要“上传文件/生成文件/持久化目录”，Runtime(102) 仍然需要提供 volume（或上对象存储/数据库）。
  - 第一阶段强烈建议：先把用户应用当作**尽量无状态**服务跑通闭环；确实需要持久化再引入 volume/OSS。

### 1.5 你们是“前后端一体 Node 项目”，Mongo 数据在部署后怎么处理？

你们 Workspace 目前的 Mongo 属于**开发态便利能力**（默认仅容器内 `127.0.0.1:27017` 可访问）。真实部署后，Mongo 必须变成**运行态独立资源**，否则会出现：

- Runtime(102) 上的应用容器无法访问 Workspace(87) 容器内 `127.0.0.1` 的 Mongo
- Workspace 容器回收/重建会导致线上数据丢失（如果数据只在容器内，且没做宿主机 volume）

因此建议把 Mongo 分成两套：

- **开发态 Mongo（Workspace）**：继续按当前方式（容器内/用户级/项目级 dbName），用于预览与开发
- **运行态 Mongo（Deploy/Runtime）**：独立 Mongo 服务（或托管 Mongo），由运行态应用通过 `MONGODB_URI` 连接

运行态 Mongo 的三种选型（按推荐顺序）：

1) **托管 Mongo（推荐）**：稳定、备份/高可用由云产品负责（第一阶段最省事）
2) **自建 Mongo（单独宿主机/单独服务）**：例如先在 91 或 102 上跑一个 `mongod`（systemd 或容器都行），并挂载 volume 做持久化
3) **每个 app 一个 Mongo 容器（不推荐）**：运维复杂，资源浪费，备份/升级麻烦

第一阶段（最小闭环）推荐口径：

- 先统一只支持 **一套“共享运行态 Mongo”**（一个 mongod，多库隔离：`db_{appId}`）
- Runtime 部署应用容器时注入：
  - `MONGODB_URI=mongodb://<mongoHost>:27017/db_{appId}`
  - （可选）用户名/密码：`MONGO_USER`/`MONGO_PASSWORD`
- Mongo 端口 **不对公网开放**，只允许内网（至少允许 102 的应用容器访问）

从 Workspace 开发态迁移到运行态 Mongo（最小可用做法）：

- 如果你们认为 Workspace 的 Mongo 数据都属于**测试数据**，那么可以**不迁移数据**，只保证运行态 Mongo 的“结构性要素”一致即可（Mongo 语境下主要是：collection、index、可选的校验规则）。
  - 最简单做法：运行态使用空库，由应用启动时自动创建 collection 并创建 index（很多 ODM/初始化逻辑都支持）。
  - 更稳妥做法：在 Runner 部署前增加一个 init 步骤（例如执行 `npm run db:init`），把“建库/建索引/初始化校验规则”显式化。

- 在 Workspace 容器内对某个 app 的库做导出：
  - `mongodump --uri "mongodb://127.0.0.1:27017/db_{appId}" --archive > dump.archive`
- 把 `dump.archive` 拷贝到运行态 Mongo 所在机器（或直接通过 Runner 作为中转）
- 在运行态 Mongo 执行导入：
  - `mongorestore --uri "mongodb://<mongoHost>:27017/db_{appId}" --archive < dump.archive`

---

## 2. 两条“对外地址”要区分：预览 vs 运行态

### 2.1 开发预览（Workspace）

- 路径：`/preview/...`
- 链路：公网入口(通常在 91) → 87 Nginx → workspace 容器端口

### 2.2 运行态访问（Runtime）

- 路径：`/runtime/{appId}/...`
- 链路（推荐）：公网入口（SLB/网关）→ Runtime 网关(102) → 用户应用容器(appId)

> 运行态访问不应回源到 Workspace；部署后用户访问只走 Runtime。

---

## 3. 核心通讯与鉴权矩阵（谁调谁、带什么）

### 3.1 API → Deploy（创建/查询/列表 Job）

- 端口：100:7002
- Header：`X-DEPLOY-SECRET`

### 3.2 Runtime-agent → Deploy（节点心跳）

- 端口：100:7002
- 接口：`POST /internal/runtime-nodes/heartbeat`
- Header：`X-RT-Node-Token`

### 3.3 Runner → Deploy（claim/heartbeat/report）

- 端口：100:7002
- Runner 侧周期轮询 claim；执行中 heartbeat；结束后 report

### 3.4 Runner → runtime-agent（部署/停止/状态）

- 端口：102:7005
- Header：`X-Runtime-Token`

---

## 4. 部署 Job 的 payload（建议先定义 v1 最小契约）

为了让 Runner 能执行部署，Deploy 的 Job payload 建议至少包含：

- `appId`（必填）
- **阶段 1（最小闭环）**：`image`（必填，镜像全名，Runner 直接部署无需构建）
- **阶段 2（引入构建：内网 Git）**：`repoSshUrl`、`gitRef`、`dockerfilePath`、`buildContext`、`imageRepo`

> 建议：阶段 1 先用“预构建镜像”，把链路跑通；再接入从 git 拉源码构建。

---

## 5. 分阶段落地（强烈建议按顺序）

### 5.1 阶段 1：最小闭环（不做构建，直接部署镜像）

**目标**：证明 Deploy→Runner→Runtime 的控制面/执行面/运行态闭环可用。

- **输入**：一个已存在于 Harbor 的镜像（例如 hello-world/你们自定义 demo）
- **Runner 行为**：
  - claim job
  - 调 runtime-agent `/agent/apps/deploy`（携带 appId + image）
  - report SUCCEEDED/FAILED
- **验收**：
  - Deploy：`fun_ai_deploy_job` 有记录，状态能从 PENDING→RUNNING→SUCCEEDED
  - Deploy：`fun_ai_deploy_app_run` 有 last-known
  - Runtime：容器存在，网关路由 `/runtime/{appId}` 可访问

#### 5.1.1 你现在就可以怎么做（Git 暂不可用时）

即使 103 的 Git 还没通，你也可以先用“镜像直部署”验证链路。

**步骤 A：准备一个可用镜像（推到 Harbor）**

在你本地 Windows（能访问 103 内网或已打通隧道）把一个小镜像推到 Harbor（示例用 nginx，端口为 80）：

```bash
# 例：把 nginx:alpine 推到你们 Harbor（project 以你们现网为准，比如 funaistudio）
docker pull nginx:alpine
docker tag nginx:alpine 172.21.138.103/funaistudio/demo-nginx:alpine
docker login 172.21.138.103
docker push 172.21.138.103/funaistudio/demo-nginx:alpine
```

> 建议为“用户应用制品”单独使用一个 project（你现网已新建：`funaistudio`），避免与基础镜像/运维镜像混放。
> 如果你们后续要统一端口 3000/8080，请选择对应镜像或自行构建 demo 镜像。

**步骤 B：调用 API 创建部署 Job（用户点击“部署”的等价操作）**

API 入口：

- `POST /api/fun-ai/deploy/job/create?userId={userId}&appId={appId}`

body（关键字段：`image` + `containerPort`）：

```bash
curl -sS -X POST "http://<api-host>:8080/api/fun-ai/deploy/job/create?userId=10001&appId=20002" \
  -H "Content-Type: application/json" \
  -d '{"image":"172.21.138.103/funaistudio/demo-nginx:alpine","containerPort":80}'
```

**步骤 B.1：确保 Runtime(102) 已登录 Harbor（私有仓库必做）**

因为 **拉镜像发生在 Runtime(102)**（runtime-agent 会 `podman/docker pull`），所以 102 必须先登录 Harbor：

```bash
podman login 172.21.138.103 -u <username_or_robot>
```

> 注意：请在“运行 runtime-agent 的同一个系统用户”下登录，否则凭证可能不生效（rootless/root 差异）。

**步骤 C：观察 Runner/Deploy/Runtime**

- Runner(101) 日志应看到：claim 到 job → 调用 `agent/apps/deploy` → report `SUCCEEDED`
- Deploy(100)：`GET /deploy/jobs?limit=50` 可看到状态变化（或看 DB 表）
- Runtime(102)：调用
  - `GET /agent/apps/status?appId=20002`（Header: `X-Runtime-Token`）应为 running
  - 访问：`http(s)://<runtime-gateway>/runtime/20002/`（nginx 示例会返回默认页）

若访问 `http://<runtime-node>/runtime/{appId}/` 出现 `Connection refused`：

- 说明 **Runtime 网关（Traefik/Nginx）没有在宿主机监听 80/443**
- 需要在 Runtime 节点上启动网关容器，并接入与用户容器相同的 `RUNTIME_DOCKER_NETWORK`
- Traefik（Podman）启动参考：`fun-ai-studio-runtime/README.md` 的 “网关（Traefik）快速启动”

### 5.2 阶段 2：引入 Git（内网）拉源码 + build + push（推荐）

**目标**：Runner 真正完成 “拉代码→构建镜像→推 Harbor→部署”。

建议 Runner 统一支持（SSH）：

- `repoSshUrl`（必填，例如 `ssh://git@172.21.138.103:2222/funai/u10001-app20002.git`）
- `gitRef`（可选：branch/tag/commitSha；默认 `main`）
- `knownHostsPath`（可选：建议 `/opt/fun-ai-studio/keys/gitea/known_hosts`）
- `dockerfilePath`（默认 `Dockerfile`）
- `image`（最终 push 的完整 tag）

验收：同阶段 1，但多了镜像 tag 可追溯与回滚能力。

### 5.3 阶段 3：Workspace 产出与 git 的关系（可选）

两种路线二选一：

- **路线 A（推荐）**：Workspace 只做开发，发布必须 push 到 git（Runner 从 git 拉）
- **路线 B（补充能力）**：Workspace 导出 zip/upload 到对象存储，Runner 下载构建（复杂度更高）

---

## 6. 一步一步“怎么验收”（建议照抄）

### 6.1 Deploy 是否能看到 Runtime 节点在线

在 Deploy(100)：

- `GET /admin/runtime-nodes/list`（Header: `X-Admin-Token`）

### 6.2 创建一个“阶段 1”的 job（image 直部署）

建议在 API 侧提供 create payload（或临时直接调用 Deploy `POST /deploy/jobs`）。

### 6.3 Runner 日志观察点

- claim 到 jobId + runtimeNode(agentBaseUrl/gatewayBaseUrl)
- deploy 调用 runtime-agent 成功/失败
- report 成功/失败写回 Deploy

### 6.4 Runtime 验收点

- `runtime-agent /internal/health` OK
- 用户容器存在（podman/docker ps）
- `/runtime/{appId}` 路由可访问

---

## 7. 常见坑（提前规避）

- **不要让 Runner/Runtime 依赖 Workspace 的目录**做交付：跨机复制/权限/路径差异会放大问题
- **先跑通“镜像直部署”**再做构建：把变量从 N 个降到 1 个
- **DB 权限**：91 的 MySQL 必须允许 100 访问（你已经遇到过 host not allowed）
- **网关路由**：`/runtime/{appId}` 必须明确由谁承接（102 网关还是 91 统一入口再转发）


