# Harbor 自建镜像站（103/Gitea 同机，内网优先）

你给的约束：

- 103 只有 **内网**稳定使用；可以有公网 IP 方便运维访问 Gitea
- 不想再折腾阿里云 ACR 的限制
- 你们的“docker”很多时候实际是 `podman-docker`

结论建议：

- **Harbor 选择 Docker 部署**（官方最稳、踩坑最少）；103 上可以装 Docker CE
- **镜像推拉走内网 IP**（101/102 → 103 内网），性能/稳定性最好
- **公网只用于运维访问 UI**：要么安全组白名单，要么 SSH 隧道；不要把 registry 全量暴露公网

---

## 1. 端口与冲突

103 现状（按你们文档）：

- Gitea Web：`3000`
- Gitea SSH：`2222`

Harbor 默认：

- Web/UI + Registry：`80/443`（推荐最终用 443；起步可先用 80）

**不冲突**：`3000/2222` 与 `80/443` 不重叠。

---

## 2. Harbor 的“内网 + IP”落地建议

你们目前没有域名，只用 IP：

- Harbor 的 `hostname` 建议填 **103 内网 IP**（例如 `172.21.138.103`）
- 101/102 的镜像地址写成：`172.21.138.103/funaistudio/...`

> 注意：如果你把 `hostname` 设置成公网 IP，但 101/102 用内网 IP 访问，会出现“访问地址不一致/证书不一致”的各种问题。  
> 所以：**内网 IP 作为唯一 canonical 地址**最省心。

---

## 3. 103（Git 服务器）上部署 Harbor（Docker 官方方式）

### 3.1 安装 Docker（103）

Harbor 官方安装脚本要求 **Docker Engine >= 20.10.10**。你现在的提示：

- `Emulate Docker CLI using podman...`
- `docker version: 4.x`

说明你机器上的 `docker` 其实是 **podman-docker 仿真层**，Harbor 会直接拒绝安装。

#### 3.1.1 先确认当前 docker 是否是 podman 仿真

```bash
docker version
docker info | head
which docker
```

如果看到 `Emulate Docker CLI using podman`，按下面步骤安装**真实 Docker**。

#### 3.1.2 移除 podman-docker（避免冲突）

```bash
sudo dnf remove -y podman-docker || sudo yum remove -y podman-docker
```

#### 3.1.3 安装 Docker CE（推荐）

Alibaba Cloud Linux 3（`platform:al8`）可直接复用 `centos/8` 的 Docker CE 源。阿里云机器若访问 `download.docker.com` 不稳定，推荐先用 mirrors：

```bash
sudo dnf install -y dnf-plugins-core || sudo yum install -y yum-utils

# Docker CE repo（阿里云镜像）
sudo tee /etc/yum.repos.d/docker-ce.repo >/dev/null <<'EOF'
[docker-ce-stable]
name=Docker CE Stable - x86_64
baseurl=https://mirrors.aliyun.com/docker-ce/linux/centos/8/x86_64/stable
enabled=1
gpgcheck=0
EOF

sudo dnf makecache -y
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable --now docker
docker version
```

> 如果你们 103 不能访问 `download.docker.com`，就需要用阿里云镜像源/离线 rpm（你告诉我 103 的 OS 版本，我可以给你一套“内网可用”的安装命令）。

#### 3.1.4 装完必须满足的检查点

- `docker version` 里 **Server** 版本 >= 20.10.10
- `docker ps` 能正常返回（daemon 运行中）

> 若你不想在 103 装真实 Docker：可以先用 **Docker Registry v2（`registry:2`）** 跑通镜像推拉（podman 原生支持），见下方“附录：Registry v2 最小落地”。

#### 3.1.5 常见安装失败：`containerd.io` 与 `podman/runc` 冲突

你可能会遇到类似报错：

- `containerd.io conflicts with runc`
- `podman requires runc >= ...`

原因：Docker CE 的 `containerd.io` 包会 **替换/冲突** 系统的 `runc`，而你机器上已装的 `podman` 依赖系统 `runc`，于是 DNF 无法同时满足。

**解决方式 A（推荐，Harbor 专用机思路）**：把 103 当成 “Harbor + Gitea” 的 Docker 机器，直接移除 podman 相关包，然后用 `--allowerasing` 安装 Docker CE。

> ⚠️ 这会影响你在 103 上通过 podman 跑的容器（如果 Gitea 当前是 podman 容器，需先停服/迁移）。

```bash
# 先看看 103 当前是否有 podman 容器在跑（例如 gitea）
sudo podman ps

# 如果确认可以迁移/停服，再执行移除（按需增删）
sudo dnf remove -y podman podman-catatonit buildah skopeo

# 安装 docker-ce（允许替换冲突包）
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin --allowerasing

sudo systemctl enable --now docker
docker version
docker ps
```

**解决方式 B（不动 podman）**：放弃 Harbor，先用 `registry:2`（podman 原生支持）跑通私有镜像站，后续再单独加一台 Docker 机器跑 Harbor。

#### 3.1.6 你当前这台 103：选择“彻底切到 Docker”的最短执行顺序（含 Gitea 迁移提示）

> 目标：解决你现在遇到的 `containerd.io` 与 `podman/runc` 冲突，让 Docker CE 安装成功，并且 Gitea 数据不丢。

1) **确认 Gitea 当前是否由 podman 托管**

```bash
sudo systemctl status gitea.service --no-pager -l || true
sudo podman ps -a
```

2) **如果 Gitea 在 podman 上跑：先停服（避免数据写入）并备份目录**

```bash
sudo systemctl stop gitea.service || true
sudo podman stop gitea 2>/dev/null || true
sudo podman rm gitea 2>/dev/null || true

# 备份（建议）
ts=$(date +%Y%m%d_%H%M%S)
sudo tar -zcvf "/data/funai/gitea/backups/gitea_${ts}.tar.gz" -C /data/funai gitea
ls -al "/data/funai/gitea/backups/gitea_${ts}.tar.gz"
```

3) **移除 podman 相关包（解除 runc 依赖链）**

```bash
sudo dnf remove -y podman podman-catatonit buildah skopeo
```

4) **安装 Docker CE（关键：`--allowerasing`）**

```bash
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin --allowerasing
sudo systemctl enable --now docker
docker version
docker ps
```

5) **用 Docker 把 Gitea 拉起（复用原数据目录挂载）**

> 如果 103 拉 Docker Hub 超时，可按 `git-server-gitea.md` 的 “ACR 中转” 或离线导入方式拿到镜像。

```bash
docker rm -f gitea 2>/dev/null || true
docker run -d --name gitea --restart unless-stopped \
  -p 3000:3000 \
  -p 2222:22 \
  -v /data/funai/gitea/data:/data \
  -v /data/funai/gitea/config:/etc/gitea \
  gitea/gitea:1.22.4

docker ps | grep gitea
curl -sS http://127.0.0.1:3000/ >/dev/null && echo "gitea web ok" || echo "gitea web FAIL"
```

6) **回到 Harbor 安装**

```bash
cd /data/funai/harbor/harbor
./install.sh
docker ps | egrep 'harbor|registry' || true
```

### 3.2 下载 Harbor 离线安装包

在 103：

```bash
mkdir -p /data/funai/harbor && cd /data/funai/harbor
```

#### 3.2.1 获取下载地址（推荐）

- Harbor Releases：`https://github.com/goharbor/harbor/releases`
- 找到你要的版本（例如 `v2.10.0`），下载文件：
  - `harbor-offline-installer-v2.10.0.tgz`

#### 3.2.2 直接在 103 下载（示例）

```bash
ver="2.10.0"
curl -fL -o "harbor-offline-installer-v${ver}.tgz" \
  "https://github.com/goharbor/harbor/releases/download/v${ver}/harbor-offline-installer-v${ver}.tgz"
ls -al "harbor-offline-installer-v${ver}.tgz"
```

如果你的系统没有 `curl`，用 `wget` 也行：

```bash
ver="2.10.0"
wget -O "harbor-offline-installer-v${ver}.tgz" \
  "https://github.com/goharbor/harbor/releases/download/v${ver}/harbor-offline-installer-v${ver}.tgz"
```

#### 3.2.3 103 无法联网时的兜底

在一台能联网的机器下载好 `harbor-offline-installer-vX.Y.Z.tgz`，再传到 103：

```bash
scp harbor-offline-installer-v2.10.0.tgz root@172.21.138.103:/data/funai/harbor/
```

解压：

```bash
tar -zxvf harbor-offline-installer-*.tgz
cd harbor
cp harbor.yml.tmpl harbor.yml
```

### 3.3 最小配置（先跑通）

编辑 `harbor.yml`（关键项）：

- `hostname: 172.21.138.103`
- `http: port: 80`
- 先**注释/移除 https**段落（起步不做 HTTPS，减少证书分发成本）
- `harbor_admin_password: CHANGE_ME_STRONG_PASSWORD`
- `data_volume: /data/funai/harbor/data`

然后安装：

```bash
./install.sh
docker ps | grep harbor
```

---

## 4. 101/102 配置（因为先用 HTTP）

因为 Harbor 起步用 **HTTP**，Docker/Podman 客户端需要允许 insecure registry。

### 4.1 Runner(101) Docker

编辑 `/etc/docker/daemon.json`：

```json
{
  "insecure-registries": ["172.21.138.103"]
}
```

重启：

```bash
sudo systemctl restart docker
```

### 4.2 Runtime(102) Podman

编辑 `/etc/containers/registries.conf`（路径视系统可能略不同）：

```toml
[[registry]]
location = "172.21.138.103"
insecure = true
```

### 4.3 Workspace-node(87) Podman-Docker（非常容易踩坑）

你们的 workspace 节点（workspace-node，通常在 87 大机）很多时候跑的是 **podman + podman-docker**（`docker` 命令其实是 podman 仿真层）。

此时如果 Harbor 只启用 **HTTP(80)**，podman 默认会用 **HTTPS** 访问 registry，典型报错：

```text
Trying to pull 172.21.138.103/<project>/<image>:<tag>...
... pinging container registry 172.21.138.103: Get "https://172.21.138.103/v2/": dial tcp 172.21.138.103:443: connect: connection refused
```

解决：在 workspace-node 机器上配置 insecure registry（与 102 一样）：

```toml
[[registry]]
location = "172.21.138.103"
insecure = true
```

验证（在 workspace-node 上执行）：

```bash
podman pull 172.21.138.103/<project>/<image>:<tag>
```

---

## 5. Harbor 内创建 Project 与机器人账号

1) 运维访问 UI（建议内网访问；公网访问请走安全组白名单）

- `http://<103内网IP>/`

2) 登录 admin 后创建 Project：

- Project 名：`funaistudio`（对齐你们当前 namespace）
- 可设为 private

3) 创建 Robot Account：

- Runner 用：`push + pull`
- Runtime 用：`pull`

---

## 6. 你们项目侧配置如何切换到 Harbor

你们当前代码里使用的是 `acrRegistry/acrNamespace` 字段（命名是 ACR，但本质就是 registry）：

- `acrRegistry`：改成 `172.21.138.103`（Harbor host）
- `acrNamespace`：继续用 `funaistudio`（Harbor Project）

Runner(101) 环境变量建议：

- `ACR_REGISTRY=172.21.138.103`
- `ACR_NAMESPACE=funaistudio`
- `REGISTRY_USERNAME=<harbor robot username>`
- `REGISTRY_PASSWORD=<harbor robot token>`

---

## 7. “搭顺风车”走公网 IP 的建议

可以，但我建议这样做（从安全/运维角度）：

- **Harbor registry 不对公网开放**（至少不要对全网开放）
- 如果要外网访问 UI：
  - 安全组只放行你的固定公网 IP（运维白名单）
  - 或者更推荐：用 SSH 隧道访问内网 UI（公网只开 SSH）

等你们有域名后，再考虑：

- 用 443 + 正式证书（Let’s Encrypt / 企业证书）
- 再把 insecure registry 配置去掉

---

## 附录：Registry v2 最小落地（不装 Docker / 先跑通）

如果 103 当前只有 `podman-docker` 仿真层，Harbor 官方安装会很痛苦。此时可以先用 `registry:2` 跑通“101 push、102 pull”，后续再单独迁移到 Harbor。

### A.1 端口与目录

- Registry：`5000/tcp`（**仅内网**）
- 数据目录：`/data/funai/registry`

### A.2 103 上启动（docker/podman 均可）

```bash
mkdir -p /data/funai/registry/data

docker rm -f funai-registry 2>/dev/null || true
docker run -d --name funai-registry --restart=always \
  -p 5000:5000 \
  -v /data/funai/registry/data:/var/lib/registry \
  registry:2
```

> 如果你们需要认证（推荐）：请改成 `htpasswd` 方式（之前的 `self-hosted-registry-103.md` 已合并删除，避免目录里出现两套“镜像站方案”文档）。

### A.3 101/102 配置 insecure registry（HTTP）

- 101（Docker）：`/etc/docker/daemon.json` 增加 `insecure-registries: ["172.21.138.103:5000"]` 后重启 docker
- 102（Podman）：`/etc/containers/registries.conf` 增加 `location="172.21.138.103:5000", insecure=true`


