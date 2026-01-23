# Workspace：Git 集成（Workspace-dev ↔ Git Server/Gitea）

本文档描述 **Workspace 节点（87 / workspace-dev）** 如何与 **Git 服务器（103 / Gitea）**交互，把 Git 作为源码“唯一真相源（Source of Truth）”，而 Workspace 作为“开发态工作区（working copy）”。

---

## 1. 目标与边界

### 1.1 目标（推荐口径）

- **Git（103/Gitea）是唯一真相源**：代码版本、审计、回滚、协作、权限都以 Git 为准
- **Workspace（87）是开发态 working copy**：
  - 用户在 Workspace 容器内编辑/运行/安装依赖
  - 通过 `git commit/push` 把变更提交回 Git（而不是依赖“自动同步”）
- **部署（Runner 101）永远从 Git 拉**：不依赖 Workspace 的目录做交付（避免跨机复制/路径权限差异）

### 1.2 非目标（第一阶段不做）

- **不做“实时双向自动同步”**（容易冲突、不可审计、排障困难）
- **不把所有项目做成一个大仓库**（monorepo 可后续支持，第一阶段推荐“每 appId 一个仓库”）
- **Runtime（102）的用户应用容器不需要 Git**（运行态只跑镜像产物，避免体积/攻击面增加）

---

## 2. 统一命名约定（必须固定，才能自动化）

### 2.1 Gitea 组织（owner）

- 统一 owner：`funai`

### 2.2 仓库命名（每 appId 一个仓库，推荐）

- repo：`u{userId}-app{appId}`

示例：

- userId=10001, appId=20002
- 远端仓库：`funai/u10001-app20002`
- SSH clone URL：
  - `ssh://git@172.21.138.103:2222/funai/u10001-app20002.git`

> 说明：ACR 的 namespace（例如 `funshion`）与 Gitea 的 owner（`funai`）是两套概念，不冲突。

---

## 3. Workspace 落盘与项目目录布局

Workspace 的宿主机持久化目录（已在 Workspace 总览里约定）：

- 宿主机：`{hostRoot}/{userId}/apps/{appId}`
- 容器：`/workspace/apps/{appId}`

Git 集成后，每个 `apps/{appId}` 目录就是一个独立 working copy：

- 包含 `.git/`（本地仓库元数据）
- 不建议把 `node_modules/`、`dist/`、`build/` 之类纳入 Git（由 `.gitignore` 控制）

---

## 4. “创建项目 / 进入编辑器 / 部署”三条链路怎么衔接

### 4.1 创建项目（app 创建）

推荐流程（最终形态）：

1) API 创建 `fun_ai_app` 记录 → 获取 `appId`
2) API 调用 Gitea API：在 `funai` 下创建仓库 `u{userId}-app{appId}`
3) （可选）初始化仓库内容：README、.gitignore、Dockerfile 模板等

降级策略（Git 暂不可用时）：

- **不要阻塞 app 创建**：先创建 app 记录，后续在第一次进入 Workspace / 第一次部署时再补建仓库（lazy create）

### 4.2 进入在线编辑器（Workspace open-editor）

建议在 `ensure-dir`（或 open-editor 聚合入口）阶段做：

- 若 `apps/{appId}` 目录不存在：执行 `git clone`
- 若目录已存在且是 git repo：按策略执行 `git fetch`/`git pull`

策略建议（避免误覆盖用户未提交改动）：

- 默认不强制 `pull --rebase` 覆盖
- 若工作区有未提交改动：
  - 提示用户先 commit
  - 或提供按钮：stash → pull → pop（可选增强）

### 4.3 用户点击部署（Deploy）

部署不依赖 Workspace：

- Runner(101) 从 Deploy claim 到 job payload（包含 `repoSshUrl/gitRef`）
- Runner 用 SSH 从 Git 拉代码 → build → push 镜像 → 调 runtime-agent(102) 部署

> 这样 Git 是单一真相源：Workspace 只是开发态；Runner 是发布态。

---

## 5. Git 鉴权/密钥设计（安全最关键）

需要分离两种身份：

### 5.1 Workspace（写入：commit/push）

Workspace 需要 push，建议两种模式二选一：

- **模式 A（推荐后期）**：每用户独立 Gitea 账号 + 用户自己的 SSH key
  - 优点：权限清晰、可审计、最符合 Git 使用习惯
  - 缺点：需要用户体系与 Gitea 账号/权限绑定（需做集成）

- **模式 B（第一阶段可用）**：平台代提交（Workspace 使用平台统一账号/Key）
  - 优点：落地快
  - 缺点：审计粒度差（只能看到平台账号在 push）
  - 建议：commit message 里带上 userId/appId，并在 API 层做额外审计记录

### 5.2 Runner（只读：clone/pull）

Runner 不需要 push，建议：

- **每个 repo 一个只读 Deploy Key**（Gitea Repo Settings → Deploy Keys → Read-only）
- Runner 在 clone 时显式指定 key + known_hosts

---

## 6. Workspace-node 需要提供哪些“Git 能力”（建议做成按钮/接口）

第一阶段最小集：

- `git/clone`：目录不存在时 clone
- `git/pull`：拉取更新（用户点击或进入编辑器时触发）
- `git/status`：展示是否有未提交改动
- `git/commit-push`：提交并推送（可选，第一阶段可先让用户在终端手动执行）

> 说明：如果你们当前 Workspace 已支持 WebSocket 终端，第一阶段也可以先用“终端手动 git”跑通体验，再逐步把常用操作做成按钮。

---

## 7. 安全组/网络约束（必须满足）

至少需要：

- Workspace-dev(87) → Git(103) **TCP 2222**（SSH push/pull）
- （可选）运维访问 Git Web：103:3000（建议只白名单，不对公网开）

具体矩阵见：`doc/domains/server/security-groups.md`

---

## 8. 常见坑（提前规避）

- **不要自动同步 node_modules/dist**：全部走 `.gitignore`，否则仓库会爆炸
- **不要强制 pull 覆盖用户未提交改动**：必须先 status 检查
- **不要让部署依赖 workspace 目录**：否则跨机交付会很痛苦


