# ACR 运维规范：免交互登录（方案 A）+ 制品保留策略（N=3）

本文档固化两件“必须工程化”的事情：

1) **避免每次 push/pull 都手动输入密码**：采用方案 A（RAM 用户 + 机器侧一次登录 + 凭证落盘）
2) **避免镜像版本无限增长**：采用保留策略 **每 app 保留最近 N=3 个版本**，其余自动清理

适用你们现网：

- ACR 实例：个人版（限制：命名空间 3、仓库 300）
- 用户应用制品 namespace：`funaistudio`

> 备注：文档不存放任何密码/密钥，只说明流程与最小权限设计。

---

## 1. 免交互登录（方案 A：RAM 用户 + 机器侧一次登录）

### 1.1 原则：按最小权限拆账号

建议拆两类 RAM 用户（或子账号）：

- **Runner Push 账号（101）**：`push + pull`（仅允许 `funaistudio/*`）
- **Runtime Pull 账号（102）**：`pull`（仅允许 `funaistudio/*`）

好处：

- 101 泄露风险可控（只影响制品仓库，且可随时吊销）
- 102 只拉不推，权限更小

### 1.2 机器侧一次登录即可（之后不需要再手输）

在对应机器上执行一次 `login`：

- Docker：凭证落盘到 `~/.docker/config.json`
- Podman：凭证落盘到 `~/.config/containers/auth.json`

关键注意事项：

- **systemd 服务用哪个系统用户跑，就用哪个用户登录一次**
  - rootless/root 不同用户的凭证互不共享
- 登录后即可在后续 `pull/push` 中复用（不会再次提示输入密码）

### 1.3 你们现网 ACR endpoint（示例）

从你截图可见你们有两类登录域名（按网络环境选择其一）：

- **公网**：`crpi-39dn3ekytub82xl9.cn-hangzhou.personal.cr.aliyuncs.com`
- **专有网络 VPC**：`crpi-39dn3ekytub82xl9-vpc.cn-hangzhou.personal.cr.aliyuncs.com`

建议：

- 服务器位于同一 VPC：优先用 `*-vpc`（更稳、更快）
- 否则用公网域名

### 1.4 标准登录命令（示例）

Runner(101)（如果使用 docker）：

```bash
docker login <acrRegistry> -u <runnerRamUser>
```

Runtime(102)（你们用 podman）：

```bash
podman login <acrRegistry> -u <runtimeRamUser>
```

> 密码建议使用 ACR 的“固定密码/访问凭证”，避免把主账号密码写入机器。

---

## 2. 制品保留策略：每 app 保留最近 N=3 个版本

### 2.1 为什么需要保留策略

即便仓库上限是 300，**tag/manifest 也会无限增长**：

- 每次部署都生成一个新 tag（例如 `gitSha`）
- 长期不清理会导致制品仓库膨胀，影响成本与管理

因此必须引入“自动清理”。

### 2.2 你们的推荐镜像组织方式（便于清理）

- namespace：`funaistudio`（用户应用制品专用）
- repo：`apps/app-{appId}`（每 app 一个仓库）
- tag：`{gitSha}`（可追溯/可回滚）

示例：

- `.../funaistudio/apps/app-20000254:acde123`

### 2.3 保留规则（N=3）

对每个 `appId`：

- 保留最近 **3** 个 “成功部署产物”的 tag（按推送时间/创建时间排序）
- 可选：保留一个指针 tag（如 `latest`），但注意 `latest` 不算入 N（或算入 N，二选一，口径要定死）

### 2.4 自动清理怎么做（两种实现）

#### 方案 1：ACR 生命周期策略（如果个人版支持）

在 ACR 控制台配置：

- 每个 repo 保留最近 3 个版本
- 或按时间保留（如 7 天）+ 上限 3

优点：零代码。

#### 方案 2：平台定时任务清理（通用，推荐兜底）

在 91/100 任一台跑一个定时任务（cron/systemd timer）：

- 调用 ACR OpenAPI（或 `aliyun` CLI）：
  - 列出 repo/tag
  - 按时间排序
  - 删除超出 N=3 的旧 tag

优点：不依赖控制台能力；逻辑可控；可加白名单保护（例如保护 `latest/stable`）。

---

## 3. 下一步落地清单（你后续要做但现在不必立刻做）

- 为 Runner 与 Runtime 创建 RAM 用户并最小授权（push/pull vs pull-only）
- 在 101/102 以 systemd 运行用户执行一次 `login`
- 在 ACR 控制台或平台侧落地 N=3 清理策略（避免长期膨胀）


