# Deploy（API 入口与调用链）

本域文档描述：用户点击“部署”时，**用户/前端只访问 API 服务**，API 再去调用 `fun-ai-studio-deploy`（部署控制面）创建 Job，Runner 轮询领取并执行部署动作。

推荐先读：

- [Deploy：整体架构与互联矩阵](./architecture.md)
- [真实部署闭环落地计划（现网 6 台）](./real-deploy-rollout.md)
- [标准流水线：Git 作为源码真相源 + ACR 作为唯一制品](./git-acr-pipeline.md)
- [必做自动化：Gitea 仓库创建 + 授权 + Runner 拉代码](./gitea-automation.md)
- [运行态数据库（Mongo）方案 A：独立 Mongo（推荐）](./runtime-mongo.md)
- [ACR 运维规范：免交互登录（方案 A）+ 制品保留策略（N=3）](./acr-auth-and-retention.md)
- [Dockerfile 规范（统一构建/部署契约）](./dockerfile-standards.md)
- [方案 C：自建 Git（内网）——在 103 部署 Gitea（SSH 拉代码）](./git-server-gitea.md)
- [Harbor 自建镜像站（103）](./harbor-103.md)

运维落地（服务器/安全组/联调）：

- [多机扩容方案（面向 Deploy / Runner / Runtime）](../server/scaling-deploy-runtime.md)

## 1. 三者关系（控制面 / Runner / 用户应用 Runtime）

- **控制面（Deploy 服务）**：决定做什么（创建任务、记录状态、审计、分配 Runner），不直接执行用户代码
- **Runner（执行面）**：领取任务后执行构建/部署，并回传结果
- **用户应用（Runtime 容器）**：最终对外提供服务的应用进程/容器（用户访问的 `https://{domain}/apps/{appId}/...` 指向它）

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

## 4. API -> Deploy 控制面配置

配置项（见 `application*.properties`）：

- `deploy-proxy.enabled`
- `deploy-proxy.base-url`
- `deploy-proxy.shared-secret`（**API -> Deploy 内部鉴权密钥**，Deploy 侧校验 Header：`X-DEPLOY-SECRET`）


