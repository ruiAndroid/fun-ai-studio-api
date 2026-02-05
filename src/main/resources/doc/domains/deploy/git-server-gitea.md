# 方案 C：自建 Git（内网）——103 上从零部署 Gitea（SSH 拉代码）

你已确认：**代码必须在内网**，并且你有一台空闲服务器：

- Git（Gitea）：`172.21.138.103`

本页是一份可以照抄执行的 SOP：把 103 从“空白机”变成可用的 Git 真相源，并满足：

- Workspace-dev(87) 能 push/pull
- Runner(101) 能用 SSH 拉代码（只读 deploy key）
- API 在创建 Deploy Job 时自动注入 `repoSshUrl/gitRef`

---

## 0. 端口约定（推荐固定）

- **Gitea Web**：`103:3000`（仅内网/运维访问）
- **Gitea SSH（容器内置 SSH 服务）**：`103:2222`（仅内网，给 Workspace/Runner 用）

> 说明：用 `2222` 避免占用宿主机 `22`（运维登录口）。  
> 统一使用 `ssh://git@172.21.138.103:2222/<owner>/<repo>.git`，避免端口歧义。

---

## 1. 安全组 / 防火墙放行（先做，避免装完发现连不上）

### 1.1 安全组（云控制台）

- **入站 TCP 2222**：允许来源 **`172.21.138.87/32`、`172.21.138.101/32`**
- **入站 TCP 3000**：仅允许运维白名单（或临时允许 `172.21.138.91/32` 进行初始化）
- **不要**对公网开放 `2222/3000`

### 1.2 103 本机防火墙（如果启用了 firewalld）

```bash
sudo systemctl status firewalld --no-pager || true
sudo firewall-cmd --state || true

sudo firewall-cmd --permanent --add-port=3000/tcp
sudo firewall-cmd --permanent --add-port=2222/tcp
sudo firewall-cmd --reload

sudo firewall-cmd --list-ports
```

> 如果你们用的是 iptables/其它防火墙策略，原则一样：只开 3000/2222，来源尽量收敛。

---

## 2. 103 安装容器运行时（Docker 或 Podman 二选一）

> 说明（阿里云常见情况）：你在服务器上输入 `docker`，如果看到提示：
> `Emulate Docker CLI using podman...`
> 那说明 **docker 命令其实在调用 Podman**（兼容层）。这没问题，你仍然可以照 `docker run ...` 的方式启动容器。
> 但要注意：**开机自启**建议用 `podman generate systemd`（见下文）。

> 推荐：你们 Runtime(102) 已经用 Podman，103 也可以统一 Podman；但 Docker CE 也没问题。

### 2.1 Docker（CentOS/RHEL 系常见）

```bash
sudo yum -y install yum-utils
sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo yum -y install docker-ce docker-ce-cli containerd.io
sudo systemctl enable --now docker
docker version
```

#### 2.1.1 常见问题：下载 `containerd.io` 失败（SSL connect error / Cannot download）

在阿里云/内网环境里，`download.docker.com` 可能被网络策略拦截或 TLS 握手异常，表现为：

- `Curl error (35): SSL connect error ... download.docker.com:443`
- `containerd.io: Cannot download, all mirrors were already tried without success`
- `docker: command not found` / `docker.service does not exist`

处理建议（按优先级）：

1) **直接切换 Podman（推荐）**：见下节 `2.2`（并安装 `podman-docker` 兼容 `docker` 命令）
2) 如果你们必须用 Docker CE：将 repo 切到可达的镜像源（例如阿里云镜像），再重试安装

> 重要：如果你已经添加了 `docker-ce.repo`，但 `download.docker.com` 不可达，
> 那么 **yum 可能会因为该 repo 拉元数据失败而中断任何安装**（包括安装 podman）。
> 这时先把 docker repo 禁用/删除，再安装 podman：
>
> ```bash
> sudo yum-config-manager --disable docker-ce-stable || true
> sudo rm -f /etc/yum.repos.d/docker-ce.repo || true
> sudo yum clean all
> sudo yum makecache
> ```

### 2.2 Podman（CentOS/RHEL 系常见）

```bash
sudo yum -y install podman
podman version
```

如果你希望 `docker` 命令直接可用（兼容层），可额外安装：

```bash
sudo yum -y install podman-docker
docker --version
```

---

## 3. 103 部署 Gitea（容器方式，推荐）

### 3.1 创建持久化目录

```bash
sudo mkdir -p /data/funai/gitea/{data,config,backups}
sudo chown -R 1000:1000 /data/funai/gitea
```

### 3.2 启动容器（Docker）

```bash
docker run -d --name gitea \
  --restart unless-stopped \
  -p 3000:3000 \
  -p 2222:22 \
  -v /data/funai/gitea/data:/data \
  -v /data/funai/gitea/config:/etc/gitea \
  gitea/gitea:1.22.4
docker ps | grep gitea
```

### 3.3 启动容器（Podman）

```bash
podman run -d --name gitea \
  -p 3000:3000 \
  -p 2222:22 \
  -v /data/funai/gitea/data:/data \
  -v /data/funai/gitea/config:/etc/gitea \
  docker.io/gitea/gitea:1.22.4
podman ps | grep gitea
```

#### 3.3.1 Podman（重要）：配置开机自启（systemd）

Podman 的“开机自启”推荐通过 systemd 托管（比 `--restart` 更可靠）：

```bash
sudo podman generate systemd --new --name gitea --files
sudo mv container-gitea.service /etc/systemd/system/gitea.service
sudo systemctl daemon-reload
sudo systemctl enable --now gitea.service
sudo systemctl status gitea.service --no-pager -l
```

> 如果你的系统生成的文件名不是 `container-gitea.service`，以实际输出为准（`ls -al *.service` 查看）。

### 3.4 健康自检（在 103 本机）

```bash
curl -sS http://127.0.0.1:3000/ >/dev/null && echo "gitea web ok" || echo "gitea web FAIL"
ss -lntp | egrep ':3000|:2222' || true
```

---

## 3.5 常见问题：拉 Docker Hub 超时（`registry-1.docker.io:443 i/o timeout`）

如果你在 103 上 pull 镜像时遇到：

- `dial tcp ...:443: i/o timeout`
- `Get "https://registry-1.docker.io/v2/": ... timeout`

这通常意味着该机器无法直连 Docker Hub（网络策略/出口限制/跨境链路不稳定）。

### 3.5.1 推荐方案：镜像中转（内网更稳）

如果 103 无法直连 Docker Hub：推荐用“镜像中转”把 `gitea/gitea:*` 先推到一个你们 **103 可达** 的 registry（例如 ACR/公司镜像站/临时 `registry:2`），然后 103 再从该 registry 拉取。

在“能联网的机器”（例如你本机 Windows，或任意可拉到 Docker Hub 的机器）：

```bash
docker pull gitea/gitea:1.22.4

# 用你的 registry 替换：<registry>/<namespace>/gitea:1.22.4
docker tag gitea/gitea:1.22.4 <registry>/<namespace>/gitea:1.22.4
docker login <registry>
docker push <registry>/<namespace>/gitea:1.22.4
```

在 103 上：

```bash
docker pull <registry>/<namespace>/gitea:1.22.4

docker rm -f gitea 2>/dev/null || true
docker run -d --name gitea \
  -p 3000:3000 \
  -p 2222:22 \
  -v /data/funai/gitea/data:/data \
  -v /data/funai/gitea/config:/etc/gitea \
  <registry>/<namespace>/gitea:1.22.4
```

### 3.5.2 备选方案：离线导入

如果 103 完全无法拉任何公网镜像，也可以离线导入：

在“能联网的机器”：

```bash
docker pull gitea/gitea:1.22.4
docker save gitea/gitea:1.22.4 -o gitea-1.22.4.tar
```

把 `gitea-1.22.4.tar` 传到 103（scp/oss/内网文件传输），然后：

```bash
docker load -i gitea-1.22.4.tar
docker images | grep gitea
```

---

## 4. Gitea 初始化（Web 向导，关键参数不要填错）

访问：`http://172.21.138.103:3000/`

首次进入会出现安装向导（Install Gitea）：

- **Database**：建议先用 SQLite（默认）
- **Server Domain**：按你实际访问入口填写
  - 若你从本机/公网访问：填 **公网 IP/域名**（例如 `182.92.60.159`）
  - 若你只在内网访问：填 **内网 IP**（例如 `172.21.138.103`）
- **SSH Server Domain**（如果界面有该项）：建议填 **内网 IP**（`172.21.138.103`），给 Runner/Workspace 用
- **SSH Port**：**必须填 2222**（因为我们是把宿主机 `2222 -> 容器内 22` 做了端口映射）
  - 如果你填成 22，后续生成的 clone URL 会指向 `:22`，但实际上宿主机监听的是 `:2222`，会导致 clone 失败
- **Gitea HTTP Listen Port**：`3000`
- **Application URL（ROOT_URL）**：按你实际访问入口填写
  - 公网访问示例：`http://182.92.60.159:3000/`
  - 内网访问示例：`http://172.21.138.103:3000/`

完成后创建第一个管理员账号（例如 `admin`）。

> 如果初始化后发现 SSH 端口/域名填错：可以改 `/data/funai/gitea/config/app.ini`（容器方式通常会生成），改完重启容器即可。

### 4.1 数据库选 MySQL（103 本机单独部署）时的注意事项

如果你选择在 **103 本机安装 MySQL**（而 Gitea 跑在容器里），请注意：

- **Gitea 容器里填 `localhost:3306` 会连到容器自身**，不是宿主机 MySQL  
  - 因此数据库主机应填：`172.21.138.103:3306`
- 建议为 Gitea 单独创建数据库与账号（不要用 root）

初始化最小命令（在 103）：

```bash
# 1) 首次安装若 root 是空密码（initialize-insecure），可直接登录：
mysql -uroot
```

在 MySQL 里执行：

```sql
-- 2) 给 root 设置强密码（建议立即做）
ALTER USER 'root'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG_ROOT_PASSWORD';
FLUSH PRIVILEGES;

-- 3) 创建 gitea 库与账号
CREATE DATABASE gitea CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'gitea'@'%' IDENTIFIED BY 'CHANGE_ME_STRONG_GITEA_PASSWORD';
GRANT ALL PRIVILEGES ON gitea.* TO 'gitea'@'%';
FLUSH PRIVILEGES;
```

然后在 Gitea 安装向导里填写：

- 数据库类型：MySQL
- 数据库主机：`172.21.138.103:3306`（不要填 localhost）
- 用户名：`gitea`
- 密码：上面设置的 `CHANGE_ME_STRONG_GITEA_PASSWORD`
- 数据库名：`gitea`

---

## 5. owner 统一约定：使用组织 `funai`

你已决定 owner 用 `funai`（组织），按这个做：

1) 在 Gitea Web 里创建 Organization：`funai`  
2) 仓库命名：每个 appId 一个 repo（推荐模板）  

- repo：`u{userId}-app{appId}`

示例（userId=10001, appId=20002）：

- repo：`funai/u10001-app20002`
- SSH clone：
  - `ssh://git@172.21.138.103:2222/funai/u10001-app20002.git`

---

## 6. SSH Key 策略（必须分离：Workspace 写入 vs Runner 只读）

### 6.1 Runner（101）：只读 Deploy Key（每 repo 一把，推荐）

在 Runner(101)：

```bash
sudo mkdir -p /opt/fun-ai-studio/keys/gitea
sudo ssh-keygen -t ed25519 -N "" -f /opt/fun-ai-studio/keys/gitea/app-20002-ro -C "runner-ro-app20002"
```

把 `app-20002-ro.pub` 添加到 Gitea：

- Repo → Settings → Deploy Keys → Add Deploy Key
- 勾选：**Read-only**

### 6.2 Workspace-dev（87）：写入用 Key（建议按用户/按项目）

Workspace 需要 push，不能复用 Runner 的只读 key。建议：

- 每个用户一个 SSH key（用户级），或每个项目一个 key（项目级）
- 将公钥加入对应 Gitea 账号（User Settings → SSH Keys）

---

## 7. known_hosts（避免 StrictHostKeyChecking 阻塞自动化）

在 Runner(101) 上写入：

```bash
sudo mkdir -p /opt/fun-ai-studio/keys/gitea
sudo ssh-keyscan -p 2222 172.21.138.103 | sudo tee -a /opt/fun-ai-studio/keys/gitea/known_hosts >/dev/null
```

验证 clone（示例）：

```bash
GIT_SSH_COMMAND='ssh -p 2222 -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/opt/fun-ai-studio/keys/gitea/known_hosts -i /opt/fun-ai-studio/keys/gitea/app-20002-ro' \
  git ls-remote ssh://git@172.21.138.103:2222/funai/u10001-app20002.git
```

---

## 8. 备份（第一阶段就做，别等数据丢了）

Gitea 关键资产：

- `/data/funai/gitea/data`
- `/data/funai/gitea/config`

最小备份脚本（103）：

```bash
sudo tee /usr/local/bin/backup-gitea.sh >/dev/null <<'SH'
#!/usr/bin/env bash
set -euo pipefail
ts="$(date +%F_%H%M%S)"
src="/data/funai/gitea"
dst="/data/funai/gitea/backups/gitea_${ts}.tar.gz"
tar -C "$src" -czf "$dst" data config
find /data/funai/gitea/backups -type f -name 'gitea_*.tar.gz' -mtime +7 -delete
echo "backup ok: $dst"
SH
sudo chmod +x /usr/local/bin/backup-gitea.sh

sudo /usr/local/bin/backup-gitea.sh
ls -al /data/funai/gitea/backups | tail
```

加 cron（每天 3:30）：

```bash
echo "30 3 * * * root /usr/local/bin/backup-gitea.sh >> /var/log/backup-gitea.log 2>&1" | sudo tee /etc/cron.d/backup-gitea
```

---

## 9. API 侧已经做好的事（你不需要再手动拼 repoSshUrl）

在 API 的 `application-prod.properties` 已加入：

- `deploy-git.ssh-host=172.21.138.103`
- `deploy-git.ssh-port=2222`
- `deploy-git.repo-owner=funai`
- `deploy-git.repo-name-template=u{userId}-app{appId}`
- `deploy-git.default-ref=main`

因此你调用 API 创建部署 Job 时（只传 userId/appId），API 会自动注入：

- `repoSshUrl=ssh://git@172.21.138.103:2222/funai/u{userId}-app{appId}.git`
- `gitRef=main`



