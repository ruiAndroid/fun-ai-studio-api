# Deploy（API 入口与调用链）

本域文档描述：用户点击“部署”时，**用户/前端只访问 API 服务**，API 再去调用 `fun-ai-studio-deploy`（部署控制面）创建 Job，Runner 轮询领取并执行部署动作。

推荐阅读顺序（**3 篇主线必读**）：

- [整体架构与互联矩阵（Deploy / Runner / Runtime）](./architecture.md)
- [真实部署闭环落地计划（现网 6 台）](./real-deploy-rollout.md)
- [标准流水线（Git + Harbor Registry）](./git-acr-pipeline.md)

附录（按需阅读）：

- **Git 自动化**： [Gitea 仓库创建 + 授权 + Runner 拉代码](./gitea-automation.md)
- **构建契约**： [Dockerfile 规范（统一构建/部署契约）](./dockerfile-standards.md)
- **镜像仓库落地**： [Harbor 自建镜像站（103）](./harbor-103.md)
- **Harbor 运维**： [免交互登录 + 制品保留策略（N=3）](./acr-auth-and-retention.md)（文件名历史原因保留 `acr-` 前缀）
- **运行态数据库**： [运行态 Mongo（推荐）](./runtime-mongo.md)
- **Git 服务器落地**： [103 上部署 Gitea（SSH 拉代码）](./git-server-gitea.md)

---

## 0. 关键口径（以代码实现为准）

### 0.1 containerPort（默认 3000）

- API 创建 Job 时默认补齐：`containerPort=3000`
- Runner 未显式传入时默认：`containerPort=3000`
- runtime-agent 模型默认：`containerPort=3000`

> 备注：runtime-agent **不会**自动注入 `PORT` 环境变量；`containerPort` 主要用于写入网关（Traefik）路由 label。**因此镜像内应用必须真实监听该端口**。

### 0.2 镜像命名（当前 Runner 实现）

当 payload 未指定 `image`（需要 Runner 构建）时，Runner 使用（字段名是历史命名，语义是“registry 地址/namespace”）：

- `acrRegistry`（现网为 Harbor(103) 地址，例如 `172.21.138.103`）
- `acrNamespace`（现网为 `funaistudio`）
- `imageTag`（默认 `latest`）

最终镜像为：

- `image = {acrRegistry}/{acrNamespace}/u{userId}-app{appId}:{imageTag}`

### 0.3 两种 Job 模式（阶段 1 / 阶段 2）

- **阶段 1（镜像直部署）**：payload 传 `image` + `containerPort`
- **阶段 2（Git 构建）**：payload 不传 `image`，由 Runner 使用 `repoSshUrl/gitRef` 拉代码构建，并用 `acrRegistry/acrNamespace/imageTag` 推送镜像

---

运维落地（服务器/安全组/联调）：

- [多机扩容方案（面向 Deploy / Runner / Runtime）](../server/scaling-deploy-runtime.md)
  - **含重要新特性**：[Runtime 节点磁盘水位调度与扩容到 3 台](../server/scaling-deploy-runtime.md#6-runtime-节点磁盘水位调度与扩容到-3-台推荐生产策略)

## 1. 三者关系（控制面 / Runner / 用户应用 Runtime）

- **控制面（Deploy 服务）**：决定做什么（创建任务、记录状态、审计、分配 Runner），不直接执行用户代码
- **Runner（执行面）**：领取任务后执行构建/部署，并回传结果
- **用户应用（Runtime 容器）**：最终对外提供服务的应用进程/容器（用户访问的 `https://{domain}/runtime/{appId}/...` 指向它）

## 2. 推荐调用链

1. 前端 → **API**：创建部署任务（API 校验权限/归属）
2. API → **Deploy 控制面**：创建 `PENDING` Job
3. Runner → **Deploy 控制面**：claim / heartbeat / report
4. 用户访问 → 网关 → **Runtime 容器**

## 3. API 入口（当前已实现）

Base：`/api/fun-ai/deploy`

### 3.1 创建部署 Job

- `POST /api/fun-ai/deploy/job/create?userId={userId}&appId={appId}`
- body（可选）：扩展 payload（Map）
  - 阶段 1（镜像直部署）建议传：`{ "image": "...", "containerPort": 80 }`
  - 阶段 2（Git 构建）建议传：`{ "repoSshUrl": "...", "gitRef": "main" }`
- 行为：API 会调用 deploy 控制面 `POST /deploy/jobs` 创建 `BUILD_AND_DEPLOY` Job

### 3.2 查询 Job

- `GET /api/fun-ai/deploy/job/info?jobId={jobId}`

### 3.3 列表 Job

- `GET /api/fun-ai/deploy/job/list?limit=50`

### 3.4 查询当前用户已部署的应用

- `GET /api/fun-ai/deploy/app/list?userId={userId}`
- 可选：`&appId={appId}`（只查询单个应用）
- 行为：返回 appStatus in (DEPLOYING, READY, FAILED) 的应用列表（best-effort 填充 deployAccessUrl，仅 READY 才有值）

## 4. API -> Deploy 控制面配置

配置项（见 `application*.properties`）：

- `deploy-proxy.enabled`
- `deploy-proxy.base-url`
- `deploy-proxy.shared-secret`（**API -> Deploy 内部鉴权密钥**，Deploy 侧校验 Header：`X-DEPLOY-SECRET`）


