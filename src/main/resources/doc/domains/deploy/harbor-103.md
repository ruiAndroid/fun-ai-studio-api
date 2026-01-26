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

按你们系统选择安装方式（Alibaba Cloud Linux 3 / CentOS / RHEL 系为例）：

```bash
sudo dnf install -y docker || sudo yum install -y docker
sudo systemctl enable --now docker
docker version
```

> 若你们 103 已经是 Podman，并且不想装 Docker：可以尝试 podman-compose，但 Harbor 官方对 Docker Compose 支持最好；建议这里直接用 Docker。

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


