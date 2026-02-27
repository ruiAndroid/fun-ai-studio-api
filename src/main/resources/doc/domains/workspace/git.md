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

> 说明：镜像仓库（Harbor/ACR）的 project/namespace（例如 `funaistudio`）与 Gitea 的 owner（`funai`）是两套概念，不冲突。

---

## 3. Workspace 落盘与项目目录布局

Workspace 的宿主机持久化目录（已在 Workspace 总览里约定）：

- 宿主机：`{hostRoot}/{userId}/apps/{appId}`
- 容器：`/workspace/apps/{appId}`

Git 集成后，每个 `apps/{appId}` 目录就是一个独立 working copy：

- 包含 `.git/`（本地仓库元数据）
- 不建议把 `node_modules/`、`dist/`、`build/` 之类纳入 Git（由 `.gitignore` 控制）

### 3.1 本地目录名与远端 repo 名不一致怎么办？

这是**正常且推荐**的：

- **远端仓库名（Gitea）**：`u{userId}-app{appId}`（用于 Git 侧唯一定位、便于权限与审计）
- **本地工作目录（Workspace）**：`.../{userId}/apps/{appId}`（用于 Workspace 侧稳定定位、便于与现有 run/files 系统集成）

Git 并不要求本地目录名必须等于仓库名。你只需要把仓库 clone 到目标目录即可：

```bash
git clone <repoSshUrl> /data/funai/workspaces/{userId}/apps/{appId}
```

> 说明：`git clone` 支持“指定目标目录”，因此不会在本地创建 `u{userId}-app{appId}` 这个目录名。

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

#### 4.2.2 `open-editor` vs `git/ensure`：各自的执行时机（推荐）

核心原则：**open-editor 负责“打开编辑器所需的最小信息闭环”，git/ensure 负责“把代码同步到工作区”**，两者拆开避免把 Git 的耗时/失败风险塞进 open-editor。

- **open-editor（进入编辑器页面时立刻调用，必须成功）**：
  - 做什么：校验 app 归属/权限 → ensure-dir（确保 workspace 目录存在、必要时确保 workspace 容器运行）→ 返回目录信息/hasPackageJson/runStatus
  - 不做什么：不做 git clone/pull（避免网络/鉴权问题把“打开编辑器”卡死）

- **git/status（进入页面后并行调用，可选但推荐）**：
  - 做什么：判断当前目录是否为 git repo、是否 dirty、当前分支/commit、是否已绑定 remote
  - 作用：前端据此决定是否展示“从 Git 初始化/拉取更新/推送提交”等按钮，以及是否允许自动 pull

- **git/ensure（在 open-editor 成功后触发，推荐“后台自动 + 安全条件”）**：
  - **自动触发（建议）**：open-editor 成功返回后，前端立即调用一次 git/ensure（异步展示进度/可重试），但只在满足安全条件时才真正执行：
    - 目录为空 → 执行 clone
    - 目录是 git repo 且工作区干净（not dirty）→ 执行 pull（或 fetch + fast-forward）
    - 目录非空且非 git repo / dirty → 返回“需要用户确认”的状态（前端弹窗引导）
  - **手动触发（兜底）**：如果你担心自动同步带来的体验不确定，第一版可以只做按钮：用户点击“从 Git 初始化/拉取更新”才调用 git/ensure

> 推荐前端落地顺序（进入编辑器页）：
> 1) `POST /api/fun-ai/app/open-editor?userId&appId`（必须成功，拿到目录/状态）
> 2) 并行：`GET /workspace/git/status`（可选） + `POST /workspace/git/ensure`（可选自动）
> 3) 若 git/ensure 失败：不影响编辑器打开，只影响“代码同步”；前端提示重试/检查 SSH/权限即可

#### 4.2.1 Clone 的落地细节（建议按 appId 目录）

你们现有目录结构是按 appId 固定的，因此建议：

- clone 目标目录（容器内）：`/workspace/apps/{appId}`
- 或宿主机：`{hostRoot}/{userId}/apps/{appId}`

两者等价（bind mount 映射）。关键是：**clone 到 appId 目录**，不要创建额外层级。

兼容已有目录的情况（例如目录已存在但空、或曾经导入过 zip）：

- 若目录为空：
  - 推荐做法：删除空目录再 clone（最稳）
  - 或直接 `git clone <url> <dir>`（不同 git 版本对“空目录”兼容性略有差异）
- 若目录非空但不是 git repo：
  - 推荐提示用户“目录已有内容，是否初始化为 git 并绑定远端”
  - 可选自动化路径（谨慎）：`git init` → `git remote add origin <url>` → `git fetch` → `git checkout -t origin/main`

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

#### 5.1.1 你问的“前端 git 操作（commit/push/pull/clone）是否需要 bot？”

结论：**如果你希望前端一键 commit/push（不让用户手工管理 key），第一阶段建议引入一个 `workspace-bot`**：

- `workspace-bot`：用于 Workspace 写入（push）
- `runner-bot`：用于 Runner 只读（clone/pull）

两者必须分离、权限不同：

- `workspace-bot`：对单个用户仓库需要 **Write**
- `runner-bot`：对所有仓库仅 **Read**

后续再演进到“用户账号 + 用户自己的 SSH key”（模式 A），届时 `workspace-bot` 可逐步退场或仅用于模板导入等平台动作。

#### 5.1.2 `workspace-bot` 密钥落盘路径约定（与 runner-bot 对齐）

为了便于运维与排障，建议与 101（runner-bot）保持一致的目录结构：

- Workspace 节点（87）：`/opt/fun-ai-studio/keys/gitea/`
  - 私钥：`workspace_bot_ed25519`
  - 公钥：`workspace_bot_ed25519.pub`
  - known_hosts：`known_hosts`（建议与 key 同目录管理）

#### 5.1.3（推荐更标准）使用组织 Team 管理写权限

建议在 Gitea 组织 `funai` 下创建：

- Team：`workspace-write`（权限：Write）
- 成员：`workspace-bot`

这样 API 只需要把新建仓库纳入该 Team，就能统一管理写权限（比逐仓库 collaborator 更标准）。

### 5.2 Runner（只读：clone/pull）

Runner 不需要 push，建议：

- **runner-bot 单账号 + 单 SSH Key（推荐）**
  - Gitea：给 `runner-bot` 用户添加一把 SSH 公钥，并通过 team（推荐：`runner-readonly`）授只读
  - Runner(101)：持有对应私钥，用 `GIT_SSH_COMMAND` 或 ssh config 指向该 key + known_hosts
- 说明：这样避免为每个仓库手工/自动写 Deploy Key，扩容更简单

---

## 6. Workspace-node 需要提供哪些“Git 能力”（建议做成按钮/接口）

第一阶段最小集：

- `git/clone`：目录不存在时 clone
- `git/pull`：拉取更新（用户点击或进入编辑器时触发）
- `git/status`：展示是否有未提交改动
- `git/commit-push`：提交并推送（可选，第一阶段可先让用户在终端手动执行）

> 说明：
> - 若你们当前 Workspace 已支持 WebSocket 终端，第一阶段也可以先用“终端手动 git”跑通体验，再逐步把常用操作做成按钮。
> - 若采用 `workspace-bot`，则上述按钮会以平台身份 push；若采用“用户账号+SSH key”，则需要用户级身份与密钥管理（复杂度更高但审计更清晰）。

---

## 6.1 前端 Git 功能的可扩展路线（建议分阶段）

目标：前端逐步提供 **pull / commit+push / 查看提交历史 / 恢复到某一个版本** 等能力，但不牺牲安全性与可排障性。

### 阶段 A（建议先做，风险最低）

- **`status`**：展示工作区状态（是否 git repo、是否 dirty、当前分支/commit、remote 是否可达）
- **`ensure`/`pull`**：在“工作区干净”前提下，安全拉取（只允许 fast-forward 或普通 merge；第一版可禁用 rebase）
- **`log`**：查看最近 N 次提交（用于审计、定位问题、回退前确认）

### 阶段 B（增加写入：commit/push）

- **`commit-push`**：由前端填写 message，一键提交并 push
  - 推荐额外写入审计：userId/appId、操作者、commitSha、message、时间、IP 等（可先落 API 日志，后续再落库）
  - 默认禁止 force push（只允许快进 push）

### 阶段 C（回退/恢复版本：reset）

- **`reset`（推荐的“回退到指定版本”语义）**：将代码 reset 到指定 commit 的状态，生成新 commit 并 push
  - ⚠️ **警告：此操作会直接覆盖当前所有文件，恢复到目标版本的状态。目标版本之后的所有改动将丢失！**
  - 前端调用时需二次确认，避免误操作
- **`reset-hard`（谨慎开放）**：仅管理员/内部使用；默认不要给普通用户 UI 暴露

> 注意：reset 会覆盖文件并生成新 commit，历史仍可追溯，但目标版本之后的改动将丢失。

---

## 6.2 建议的 Workspace Git API（示例）

> 命名仅示意，你们可以挂在 workspace-node 自己的 basePath 下；关键是语义与安全边界。

### 6.2.1 `GET /workspace/git/status?userId&appId`

返回字段建议：

- **isRepo**：目录是否为 git repo
- **dirty**：是否有未提交改动（包含 staged/unstaged）
- **branch**：当前分支（可能为空：detached HEAD）
- **headCommit**：当前 HEAD commitSha
- **remoteUrl**：origin URL（若存在）
- **ahead/behind**：与 origin 的差异（可选，可能需要 fetch）

### 6.2.2 `POST /workspace/git/ensure`

请求字段建议：

- **userId/appId**
- **repoSshUrl**：例如 `ssh://git@172.21.138.103:2222/funai/u{userId}-app{appId}.git`
- **ref**：默认 `main`
- **mode**（可选）：`AUTO | CLONE_ONLY | PULL_ONLY`
- **allowOnDirty**（默认 false）：是否允许在 dirty 时继续（一般不允许；除非用户选择 stash 方案）

返回建议：

- **action**：`CLONED | PULLED | NOOP | NEED_CONFIRM`
- **reason**：例如 `DIRTY_WORKTREE`、`NOT_A_REPO`、`DIR_NOT_EMPTY`
- **headCommit**

### 6.2.3 `POST /workspace/git/pull`

约束建议：

- **dirty=false** 才允许执行
- 默认只允许 fast-forward（或普通 merge），第一阶段可以不支持 rebase

### 6.2.4 `GET /workspace/git/log?userId&appId&limit=20`

返回：

- `[{commitSha, author, email, message, time}]`

说明：

- 平台会默认过滤“仓库初始化模板提交”（例如 `init .gitignore` / `init Dockerfile` / `init .dockerignore` / `Initial commit`），避免前端误认为是用户提交。
- 过滤规则：提交 message 命中上述初始化模板且 author 命中 `funai.workspace.git.initAuthors`（默认 `funshion`）。

### 6.2.5 `POST /workspace/git/commit-push`

请求字段建议：

- **userId/appId**
- **message**
- **addAll**（默认 true）：相当于 `git add -A`

约束建议：

- 仅在启用 `workspace-bot`（或用户级身份）时开放
- 默认禁止 force push
- 若 push 失败（远端有更新）：提示用户先 pull/解决冲突

### 6.2.6 `GET/POST /workspace/git/reset`（⚠️ 谨慎操作）

请求字段建议：

- **userId/appId**
- **commitSha**：要恢复到的目标 commit

⚠️ **警告：此操作会直接覆盖当前所有文件，恢复到目标版本的状态。目标版本之后的所有改动将丢失！**

行为：

- `git fetch --all --prune`（确保目标 commit 可解析）
- `git reset --hard <commitSha>`（强制覆盖工作区与暂存区）
- `git clean -fd`（清理未跟踪文件/目录）
- `git push --force-with-lease origin HEAD`（强制推送到远端）

返回：

- **result**：SUCCESS / PUSH_FAILED / FAILED
- **commitShort**：reset 后当前 HEAD commit SHA（short）
- **targetCommit**：恢复到的目标 commit SHA
- **branch**：当前分支
- **message**：提示信息

---

## 6.3 实现方式建议（便于排障）

建议第一阶段直接调用系统 `git`（shell）而不是引入 JGit：

- **优点**：行为与开发者本地一致；遇到问题可直接在终端复现；对 LFS/子模块等兼容更好
- **要点**：所有 git 命令都必须指定工作目录为 `.../apps/{appId}`，并且做好超时/日志脱敏（不要把私钥内容打到日志）

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
