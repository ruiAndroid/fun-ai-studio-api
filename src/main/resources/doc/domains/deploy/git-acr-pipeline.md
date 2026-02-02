# 标准流水线：Git 作为源码真相源 + Harbor（103）作为唯一制品

本文档固化 FunAI Studio 的推荐发布链路：**Git（103/Gitea）只存源码**，**Harbor（103）只存制品（镜像）**，Runner 作为执行面把二者串起来，Runtime 只负责拉镜像运行。

适用现网（6 台 + 同机 Harbor）：

- API：`172.21.138.91`
- Workspace-dev：`172.21.138.87`
- Deploy：`172.21.138.100`
- Runner：`172.21.138.101`
- Runtime：`172.21.138.102`
- Git（Gitea）+ Harbor（Registry）：`172.21.138.103`

制品仓库建议使用 Harbor project（你现网：`funaistudio`）。

---

## 1. 角色分工（谁负责什么）

- **Git（Gitea）**：源码唯一真相源（版本、审计、回滚、协作、权限）
- **Workspace（开发态）**：源码 working copy（编辑/运行/依赖缓存），通过 `git commit/push` 回写 Git
- **API（入口）**：权限/归属校验 + 创建部署 Job（内部调用 Deploy 控制面）
- **Deploy（控制面）**：Job 队列、Runtime 节点注册表、placement（选址）、状态落库与审计
- **Runner（执行面）**：领取 Job，执行 “拉代码 → 构建镜像 → push Harbor → 调 Runtime 部署 → 回传结果”
- **Runtime（运行态）**：拉取镜像并运行容器，挂载网关路由 `/runtime/{appId}`

关键原则：

- **Deploy 不执行构建，不 clone 代码**
- **Runtime 不需要 Git，只运行镜像产物**

---

## 2. 标准流水线（阶段 2：含构建）

### 2.1 开发与提交（Workspace → Git）

1) 用户在 Workspace 容器（87）编辑代码
2) 用户提交并推送到 Git（103）：
   - `git commit && git push`

> 推荐：Git 成为“唯一真相源”，不要做实时自动同步；避免冲突和不可审计。

### 2.2 发起部署（用户点击“部署”）

1) 前端/用户调用 API：
   - `POST /api/fun-ai/deploy/job/create?userId={userId}&appId={appId}`
2) API 校验 `appId` 归属，并创建 Deploy Job：
   - API → Deploy：`POST /deploy/jobs`（type=BUILD_AND_DEPLOY）

> 平台约束（已落地）：
> - 同一 `appId` 部署互斥（Deploy 控制面 409）
> - 每用户运行/部署中项目数 ≤ 3（API 入口 409）

### 2.3 Runner 执行（Deploy → Runner）

1) Runner(101) 轮询领取 Job：
   - `POST /deploy/jobs/claim`
2) Runner 取到 payload（典型字段见下）后执行：
   - Git clone/pull（按 `repoSshUrl` + `gitRef`）
   - `docker/podman build`（使用统一 Dockerfile 标准）
   - push 镜像到 Harbor（唯一制品）
3) Runner 调 runtime-agent(102) 部署：
   - `POST /agent/apps/deploy`（传 image + appId + containerPort）
4) Runner 回传结果给 Deploy：
   - `POST /deploy/jobs/{jobId}/report`（SUCCEEDED/FAILED）

### 2.4 Runtime 运行（Runner → runtime-agent → 网关）

1) runtime-agent(102) 拉取镜像并启动容器
2) 网关按 label 自动路由：
   - `/runtime/{appId}/...` → 对应容器端口

---

## 3. Job payload（v1 建议）

### 3.1 阶段 1（镜像直部署：Git 不可用时的最小闭环）

payload 至少包含：

- `appId`（必填）
- `image`（必填：Harbor 镜像全名）
- `containerPort`（可选，默认 3000；按镜像实际端口设置）

Runner 行为：直接调 runtime-agent 部署镜像，不做构建。

### 3.2 阶段 2（Git 构建 + push Harbor + 部署）

payload 至少包含：

- `appId`（必填）
- `repoSshUrl`（必填，例如 `ssh://git@172.21.138.103:2222/funai/u{userId}-app{appId}.git`）
- `gitRef`（可选：branch/tag/commitSha；默认 `main`）
- `dockerfilePath`（可选，默认 `Dockerfile`）
- `buildContext`（可选，默认 repo root）
- `imageRepo`（可选：Harbor repo，如 `172.21.138.103/funaistudio/apps/app-{appId}`）
- `imageTag`（可选：建议用 `commitSha` 或 `buildNumber`）
- `containerPort`（可选，默认 3000；由 Dockerfile 标准约定）

---

## 4. Harbor 镜像组织建议（你现网：project=funaistudio）

建议：

- **project**：`funaistudio`（专门存用户应用制品）
- **repo**：`apps/app-{appId}`（每个 app 一个仓库，便于清理/审计）
- **tag**：`{gitSha}`（推荐，可追溯/可回滚）

示例：

- `172.21.138.103/funaistudio/apps/app-20002:acde123`

---

## 5. 回滚/保留策略（建议先定一个最小可用版本）

第一阶段建议“够用即可”：

- 每个 app 保留最近 `N=3` 个 tag（由 Runner 或定时任务清理旧 tag）
- 保留一个稳定指针（可选）：
  - `:latest` 或 `:stable`

更完整的运维规范（免交互登录 + N=3 清理策略）见：

- `doc/domains/deploy/acr-auth-and-retention.md`

---

## 6. 常见失败点（排障路径）

- **Git 拉取失败（Runner）**：
  - 103:2222 安全组/防火墙
  - Runner deploy key/known_hosts
  - `repoSshUrl` 拼接是否正确
- **镜像 push/pull 失败（Harbor）**：
  - 101/102 是否已 `docker/podman login 172.21.138.103`
  - Harbor project/repo 权限（建议用 Robot Account：push/pull vs pull-only）
  - 你们 Harbor 只开 **HTTP(80)**：101/102 是否已配置 **insecure registry**（否则会默认走 https 并报 443 refused）
- **Runtime 部署失败**：
  - 102 是否能访问容器运行时（podman/docker）
  - 网关/网络（Traefik labels/网络名）
  - 容器端口与 `containerPort` 是否一致


