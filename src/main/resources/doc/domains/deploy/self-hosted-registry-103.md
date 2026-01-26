# 自建镜像站（103/Gitea 同机）：Docker Registry v2（内网）

目标：在 **103（172.21.138.103）** 上部署一个私有镜像仓库，替代阿里云 ACR，用于：

- Runner(101) push 镜像
- Runtime(102) pull 镜像

> 建议：镜像站 **只开内网**（不要暴露公网），并加认证与磁盘监控。

---

## 1. 规划与端口

- 服务器：`172.21.138.103`（现有 Gitea：Web 3000，SSH 2222）
- Registry：`5000/tcp`（仅内网）
- 数据目录：`/data/funai/registry`

仓库地址示例：

- `172.21.138.103:5000/funaistudio/u10000021-app20000414:latest`

---

## 2. 103 上启动 Registry（docker 方式）

### 2.1 准备目录与账号密码

```bash
sudo mkdir -p /data/funai/registry/{data,auth}
sudo chown -R root:root /data/funai/registry

# 生成 htpasswd（需要 httpd-tools）
sudo dnf install -y httpd-tools || sudo yum install -y httpd-tools
sudo htpasswd -Bbn registry_user CHANGE_ME_STRONG_PASSWORD > /data/funai/registry/auth/htpasswd
sudo chmod 600 /data/funai/registry/auth/htpasswd
```

### 2.2 启动容器

```bash
docker rm -f funai-registry 2>/dev/null || true
docker run -d --name funai-registry --restart=always \
  -p 5000:5000 \
  -v /data/funai/registry/data:/var/lib/registry \
  -v /data/funai/registry/auth:/auth \
  -e "REGISTRY_AUTH=htpasswd" \
  -e "REGISTRY_AUTH_HTPASSWD_REALM=Registry Realm" \
  -e "REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd" \
  registry:2

docker ps | grep funai-registry
```

健康自检（在 103 本机）：

```bash
curl -sS http://127.0.0.1:5000/v2/ >/dev/null && echo "registry ok" || echo "registry FAIL"
```

---

## 3. Runner(101) 配置与验证（push）

### 3.1 Docker 配置 insecure registry（如果没有 HTTPS）

> 如果你后续给 103 配 HTTPS（推荐），这一段可以去掉。

在 101 编辑 `/etc/docker/daemon.json`：

```json
{
  "insecure-registries": ["172.21.138.103:5000"]
}
```

重启 docker：

```bash
sudo systemctl restart docker
```

### 3.2 Runner 环境变量

把 `fun-ai-studio-runner/config/runner.env` 里的：

- `ACR_REGISTRY` 改为 `172.21.138.103:5000`
- `ACR_NAMESPACE` 保持 `funaistudio`
- `REGISTRY_USERNAME / REGISTRY_PASSWORD`（推荐新变量；兼容旧 ACR_USERNAME/ACR_PASSWORD）

示例：

```bash
ACR_REGISTRY=172.21.138.103:5000
ACR_NAMESPACE=funaistudio
REGISTRY_USERNAME=registry_user
REGISTRY_PASSWORD=CHANGE_ME_STRONG_PASSWORD
```

### 3.3 手动验证 push

```bash
docker login 172.21.138.103:5000 -u registry_user
docker pull nginx:alpine
docker tag nginx:alpine 172.21.138.103:5000/funaistudio/demo-nginx:alpine
docker push 172.21.138.103:5000/funaistudio/demo-nginx:alpine
```

---

## 4. Runtime(102) 配置与验证（pull）

如果 102 用 podman：

### 4.1 配置 insecure registry

编辑 `/etc/containers/registries.conf`（按你系统版本可能路径略有不同）：

```toml
[[registry]]
location = "172.21.138.103:5000"
insecure = true
```

### 4.2 登录并拉取

```bash
podman login 172.21.138.103:5000 -u registry_user
podman pull 172.21.138.103:5000/funaistudio/demo-nginx:alpine
```

---

## 5. 迁移建议（你们当前的镜像组织方式）

目前 Runner 生成的镜像名是：

- `<registry>/<namespace>/u{userId}-app{appId}:{tag}`

建议后续演进为“每 app 一个 repo”的形式，便于做保留策略：

- `<registry>/<namespace>/apps/app-{appId}:{gitSha}`

---

## 6. 安全组/防火墙建议

103 入站仅放行（内网）：

- `5000/tcp`：仅允许 `172.21.138.101`（Runner）与 `172.21.138.102`（Runtime）访问


