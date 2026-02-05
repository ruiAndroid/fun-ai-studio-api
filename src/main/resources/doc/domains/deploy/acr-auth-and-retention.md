# Harbor 运维规范：免交互登录 + 制品保留策略（N=3）

> 文件名历史原因保留 `acr-` 前缀；本文内容以 **Harbor（103）** 为准。

本文档固化两件“必须工程化”的事情：

1) **避免每次 push/pull 都手动输入密码**：机器侧一次 `login`，凭证落盘复用
2) **避免镜像版本无限增长**：采用保留策略 **每 app 保留最近 N=3 个版本**，其余自动清理

适用你们现网（Harbor 在 103 与 Gitea 同机，仅内网 HTTP）：

- Harbor 地址（内网 canonical）：`172.21.138.103`（只开 `80`，HTTP）
- 用户应用制品 project：`funaistudio`

> 备注：文档不存放任何密码/密钥，只说明流程与最小权限设计。

---

## 1. 免交互登录（机器侧一次登录 + 最小权限账号）

### 1.1 原则：按最小权限拆账号

建议在 Harbor 的 `funaistudio` project 下创建两类账号（推荐 **Robot Account**）：

- **Runner Push 账号（101）**：`push + pull`（仅允许 `funaistudio/*`）
- **Runtime Pull 账号（102）**：`pull`（仅允许 `funaistudio/*`）

好处：

- 101 泄露风险可控（只影响制品仓库，且可随时吊销）
- 102 只拉不推，权限更小

### 1.2 机器侧一次登录即可（之后不需要再手输）

在对应机器上执行一次 `login`（**用运行服务的同一个系统用户**执行）：

- Docker：凭证落盘到 `~/.docker/config.json`
- Podman：凭证落盘到 `~/.config/containers/auth.json`

关键注意事项：

- **systemd 服务用哪个系统用户跑，就用哪个用户登录一次**
  - rootless/root 不同用户的凭证互不共享
- 登录后即可在后续 `pull/push` 中复用（不会再次提示输入密码）

### 1.3 标准登录命令（示例）

Runner(101)（如果使用 docker）：

```bash
docker login 172.21.138.103 -u <runnerHarborUserOrRobot>
```

Runtime(102)（你们用 podman）：

```bash
podman login 172.21.138.103 -u <runtimeHarborUserOrRobot>
```

> 重要：Harbor 只开 **HTTP(80)** 时，docker/podman 默认会按 HTTPS 访问，需要在 101/102 配置 **insecure registry**，否则会出现 `dial tcp ...:443: connect: connection refused`。
> 参考：`doc/domains/server/workspace-node.md` 的 “Harbor（HTTP）拉镜像必配 insecure registry”。

---

## 2. 制品保留策略：每 app 保留最近 N=3 个版本

### 2.1 为什么需要保留策略

即便仓库上限是 300，**tag/manifest 也会无限增长**：

- 每次部署都生成一个新 tag（例如 `gitSha`）
- 长期不清理会导致制品仓库膨胀，影响成本与管理

因此必须引入“自动清理”。

### 2.2 你们的推荐镜像组织方式（便于清理）

- project：`funaistudio`（用户应用制品专用）
- repo：`u{userId}-app{appId}`（当前 Runner 实现；每 app 一个仓库）
- tag：`{gitSha}`（可追溯/可回滚）

示例：

- `.../funaistudio/u10001-app20000254:acde123`

### 2.3 保留规则（N=3）

对每个 `appId`：

- 保留最近 **3** 个 “成功部署产物”的 tag（按推送时间/创建时间排序）
- 可选：保留一个指针 tag（如 `latest`），但注意 `latest` 不算入 N（或算入 N，二选一，口径要定死）

### 2.4 自动清理怎么做（两种实现）

#### 方案 1：Harbor Retention Policy（推荐）

在 Harbor UI 中对 project 配置 retention policy，例如：

 - repo 匹配：`u*-app*`
- 保留最近：`3` 个 tag（或按推送时间保留 + 上限 3）

优点：零代码、最贴近 Harbor 运维方式。

#### 方案 2：平台定时任务清理（通用，推荐兜底）

在 91/100 任一台跑一个定时任务（cron/systemd timer）：

- 调用 Harbor API（或 `curl`）：
  - 列出 repo/tag
  - 按时间排序
  - 删除超出 N=3 的旧 tag

优点：不依赖控制台能力；逻辑可控；可加白名单保护（例如保护 `latest/stable`）。

---

## 3. 下一步落地清单（你后续要做但现在不必立刻做）

- 为 Runner 与 Runtime 创建 Harbor Robot Account 并最小授权（push/pull vs pull-only）
- 在 101/102 以 systemd 运行用户执行一次 `login`
- 在 Harbor retention policy 或平台侧落地 N=3 清理策略（避免长期膨胀）


