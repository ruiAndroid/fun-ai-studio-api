# 必做自动化：Gitea 仓库创建 + 授权 + Runner 拉代码（避免手工 Deploy Key）

本页把“后续必须做的自动化”固化下来，避免因为联调阶段手工操作而遗忘。

目标：当用户创建应用/点击部署时，平台能自动完成：

- 在 Gitea（103）创建仓库
- 正确授权 Workspace 用户写入、Runner 只读拉取
- 生成/下发 `repoSshUrl`、`gitRef` 等字段给 Runner（通过 Deploy Job payload）

---

## 1. 为什么不能继续用“每仓库手工 Deploy Key”

联调阶段手工 Deploy Key 可接受，但线上不可持续：

- 每个 app 一个仓库 → 仓库数量增长很快
- 需要人工生成 key、粘贴到后台、排查权限
- 无法做权限审计/一致性检查

因此必须自动化。

---

## 2. 推荐方案（优先实现）：Runner-Bot（单账号 + 单 SSH Key + 组织只读 Team）

### 2.1 方案概述

创建一个专用机器人账号 `runner-bot`：

- 在 Gitea 的 `runner-bot` 账号下添加 **一把 SSH 公钥**
- 在组织 `funai` 下创建 Team：`runner-readonly`（Read 权限）
- 新仓库创建后，API 自动把仓库授予该 Team（或将仓库纳入组织默认可访问范围）

Runner(101) 永远使用同一把私钥拉取任何仓库：

- 不再为每个 repo 配 Deploy Key
- 不再需要手工后台操作

### 2.2 优点

- **零手工扩容**
- key 管理简单（只管一把）
- 权限模型清晰：Runner 永远只读

### 2.3 风险与控制

- `runner-bot` 私钥泄露 → 可读所有仓库
  - 控制：只读权限；私钥只放 101；文件权限 600；定期轮换；必要时分环境多把 key

---

## 3. Workspace 写入权限怎么做（两种模式）

### 3.1 模式 A（推荐后期）：用户账号 + 用户自己的 SSH Key

- 每个用户在 Gitea 有自己的账号
- 用户在 Workspace 里 push 用自己的身份
- 权限/审计最清晰

需要做的集成：

- API 用户体系 ↔ Gitea 账号绑定（可先手工）
- API 在建仓库时把仓库授权给该用户（Write）

### 3.2 模式 B（第一阶段可用）：平台代提交（统一账号写入）

- Workspace 统一使用一个平台账号（例如 `workspace-bot`）push
- 优点：落地快
- 缺点：审计粒度差（都显示 bot 提交）

建议：

- commit message 里带 `userId/appId`
- API 额外落库审计记录（谁触发了 push）

---

## 4. API 需要做的自动化点（v1）

### 4.1 新增配置（建议）

API 侧新增 `gitea.*` 配置（示例）：

- `gitea.enabled=true`
- `gitea.base-url=http://172.21.138.103:3000`
- `gitea.admin-token=...`（管理员 token，用于调用 Gitea API；Header：`Authorization: token <token>`）
- `gitea.owner=funai`
- `gitea.repo-name-template=u{userId}-app{appId}`
- `gitea.auto-init=true`（建议 true：确保存在 main 分支）
- `gitea.default-branch=main`
- `gitea.runner-team=runner-readonly`（优先：组织 team（Read））
- `gitea.runner-bot=runner-bot`（兜底：协作者 read）

### 4.2 在“创建应用（app）”时自动建仓库

流程：

1) API 创建 `fun_ai_app` → 获得 `appId`
2) API 调 Gitea API 创建 repo：
   - owner：`funai`
   - repo：`u{userId}-app{appId}`
   - private：true
   - auto_init：true（建议初始化 main 分支 + README）
3) API 将 repo 授权给：
   - `runner-readonly`（Read）
   - 用户/Workspace 写入身份（Write，视你采用模式 A 或 B）

失败策略（重要）：

- Gitea 不可用：**不阻塞 app 创建**（可标记 `repoStatus=PENDING`），后续进入 Workspace 或首次部署时重试（lazy create）

### 4.3 在“创建部署 Job”时补齐 repo 信息（你们已部分实现）

API 在 `POST /api/fun-ai/deploy/job/create` 时补齐：

- `repoSshUrl=ssh://git@172.21.138.103:2222/funai/u{userId}-app{appId}.git`
- `gitRef=main`

> 这一步已在 API 侧通过 `deploy-git.*` 实现了基础注入；后续可改为读取 app 的 repo 绑定信息，避免硬编码模板。

---

## 5. Gitea 侧一次性手工准备（只做一次）

### 5.1 创建组织与 Team

- 组织：`funai`（私有）
- Team：`runner-readonly`（Read）

### 5.2 创建 runner-bot 并配置 SSH Key

1) 创建用户：`runner-bot`
2) 将 `runner-bot` 加入组织 `funai` 的 `runner-readonly` team
3) 在 `runner-bot` 的 User Settings → SSH Keys 添加公钥（来自 101）

---

## 6. 验收清单（自动化完成后应当满足）

- 创建 app 后：Gitea 自动出现对应 repo（private）且有 main 分支
- Runner(101) 不需要 per-repo key，也能 clone 任意 repo
- Workspace 写入身份能 push（按你选择的模式 A/B）
- 部署时 Runner 使用 job payload 的 `repoSshUrl/gitRef` 拉代码构建并部署


