# Workspace：npm 依赖安装加速（Verdaccio 代理仓库）

> 双机部署提示：Verdaccio/依赖缓存通常部署在大机（容器节点）侧，workspace 容器通过容器网络访问 `http://verdaccio:4873`；小机侧只需要保持配置与转发链路正确。

## 背景与目标

在线编辑器导入的 Node 项目通常依赖较多。如果每次新项目都在容器内全量 `npm install`：

- 会很慢（重复下载）
- 容器出网不稳定时可能直接失败
- 多人并发时会造成 CPU/IO 峰值

本项目推荐使用 **Verdaccio 代理仓库**作为 npm 依赖安装加速方案：把“出网下载”集中到同机的 registry proxy 上，并缓存到服务端 `storage`，workspace 容器后续安装优先命中缓存。

## Verdaccio 代理仓库（同机容器网络访问）

Verdaccio 的收益：

- **更稳**：弱网时减少大量 registry metadata 请求，失败率更低
- **更快**：首次下载后由 Verdaccio 缓存，后续安装几乎不再出网
- **更可控**：后续可加鉴权/白名单/审计（本项目先按单机最简方式跑通）

### 部署（阿里云单机，podman-docker 兼容，仅容器网络访问）

> 重要：Verdaccio 是**运维侧常驻基础设施**。当前后端只负责创建/管理 `ws-u-{userId}` 的 workspace 用户容器，不会自动拉起 `verdaccio` 容器。

#### 手动启动/重建命令（可直接复制执行）

1) 创建网络（一次性）：

```bash
docker network create funai-net
```

2) 启动 Verdaccio（常驻 + 持久化）：

```bash
mkdir -p /data/funai/verdaccio/{conf,storage}

docker run -d --name verdaccio --restart=always \
  --network funai-net \
  -v /data/funai/verdaccio/conf:/verdaccio/conf \
  -v /data/funai/verdaccio/storage:/verdaccio/storage \
  docker.io/verdaccio/verdaccio:5
```

3) 如需“重建 Verdaccio 容器”（保留数据，仅重建容器）：

```bash
docker rm -f verdaccio 2>/dev/null || true

docker run -d --name verdaccio --restart=always \
  --network funai-net \
  -v /data/funai/verdaccio/conf:/verdaccio/conf \
  -v /data/funai/verdaccio/storage:/verdaccio/storage \
  docker.io/verdaccio/verdaccio:5
```

4) 把已有 workspace 容器加入 `funai-net`（老容器需要；新容器创建时会自动加）：

```bash
docker network connect funai-net ws-u-10000021 2>/dev/null || true
docker network connect funai-net ws-u-10000023 2>/dev/null || true
```

5) 基本验证（确认 Verdaccio 已启动且可访问）：

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"
docker logs --tail 50 verdaccio

# 在任意加入 funai-net 的容器里验证（以某个 ws-u-* 容器为例）
docker exec ws-u-10000021 bash -lc "curl -I http://verdaccio:4873 || wget -S --spider http://verdaccio:4873"
```

> 说明：本方案面向 **仅容器网络访问**，因此不需要 `-p 4873:4873` 映射到宿主机。若你要给公网/内网访问，需要额外做域名/HTTPS/鉴权/限流，并慎重开放端口。

Verdaccio 上游（uplink）建议指向 `https://registry.npmmirror.com`，由 Verdaccio 负责缓存。

### Verdaccio 基本原理（proxy + cache）

- **角色**：Verdaccio 是一个 npm registry 代理（proxy registry）。
- **工作方式**：
  - workspace 容器的 `npm install` 请求先到 Verdaccio
  - Verdaccio 若本地已有该包版本（命中缓存），直接返回
  - 未命中则转发到上游 registry（例如 `npmmirror`），拿到包后写入自身 `storage`，再返回给客户端
- **重要特性**：缓存是 **全局共享** 的（面向“包/版本”），不是“按容器/按用户”隔离的缓存桶。

### storage 是什么？存在哪里？（全局缓存）

Verdaccio 的 `storage` 是它的服务端缓存与元数据存储目录。按本文部署，宿主机目录为：

- ` /data/funai/verdaccio/storage`

一般会包含：

- **包元数据**（例如包的版本信息、dist-tags 等）
- **包 tarball**（`.tgz` 等），用于后续命中缓存

> 提醒：不建议直接手工删除 `storage` 下的文件来“清理缓存”，容易造成元数据不一致。删除/清理建议走 Verdaccio 的管理能力或做整体重建策略。

### 我怎么知道 Verdaccio 里缓存了哪些包/版本？

你可以从两个角度看：

- **通过 Verdaccio UI**：如果你不做端口映射，可以临时用一次端口转发或临时暴露（仅运维时短暂使用），查看包列表与版本。
- **直接看宿主机 storage 目录结构**：`/data/funai/verdaccio/storage` 下通常会按包名组织目录，目录中会有该包的元数据与 tarball 文件。

### 我能否知道“哪些容器缓存了哪些第三方包”？

Verdaccio 的缓存不是按容器归属的，因此**无法直接得到“容器A缓存了哪些包”这种天然映射**；它只能告诉你“哪些包/版本被请求过并缓存了”。

如果你想追踪“是谁请求了什么”，需要看 **Verdaccio 访问日志**：

- 日志能看到请求路径（包名/版本/registry API），以及请求来源（通常是容器网络里的 IP）
- 再通过 `podman/docker inspect` 把 IP 映射回 workspace 容器名，就能追溯到“哪个容器在请求哪些依赖”

### 运维建议（仅容器网络访问的最简实践）

- **日志**：开启/保留 Verdaccio 的访问日志与错误日志（用于排障与追溯谁请求了什么包）。
- **磁盘**：重点监控 ` /data/funai/verdaccio/storage` 的容量增长（Verdaccio 缓存会持续增长）。
- **备份**：定期备份：
  - `/data/funai/verdaccio/conf`
  - `/data/funai/verdaccio/storage`

### 后端配置（Spring Boot）

在 `application-prod.properties` 增加/确认：

```properties
funai.workspace.networkName=funai-net
funai.workspace.npmRegistry=http://verdaccio:4873
```

效果：

- workspace 容器创建时会 `--network funai-net`
- 容器内注入 `NPM_CONFIG_REGISTRY` / `npm_config_registry`
- 运行态脚本会把 **实际 registry** 写到 `run/dev.log`，便于排查

### 验收

- 启动/安装时查看 `run/dev.log`：应包含 `npm registry: http://verdaccio:4873`
- Verdaccio 日志可看到首次下载，后续命中缓存

## 预热（建议）：让 Verdaccio 先缓存一批常用依赖

思路：用一个临时 warmup 项目跑一次安装，但确保 registry 指向 Verdaccio，这样缓存会进入 Verdaccio `storage`：

- `npm config set registry http://verdaccio:4873`
- `npm install` 或 `npm ci`

> 验收：在 `run/dev.log` 或安装输出中确认 `npm registry` 为 `http://verdaccio:4873`。

如果你的 warmup 项目在宿主机目录（例如 `/tmp/npm-warmup`），且 Verdaccio 仅在容器网络 `funai-net` 内可访问，推荐用一个临时容器来执行预热（执行完自动删除，不占用长期资源）：

```bash
docker run --rm --network funai-net \
  -v /tmp/npm-warmup:/work -w /work \
  <你的workspace镜像> \
  bash -lc "npm config set registry http://verdaccio:4873 && (npm ci || npm install)"
```

> `<你的workspace镜像>` 建议直接使用你生产环境的 workspace 镜像（ACR），避免拉取 DockerHub 失败。

## 踩坑记录与排障清单（扩容服务器时可直接复用）

本节记录部署过程中常见问题与对应解决方式（特别是 podman-docker 环境）。

### 1) podman 运行 `verdaccio/verdaccio:5` 提示交互选择镜像来源

现象：出现 `Please select an image:`，需要交互选择 `docker.io/...` 等。

解决：使用**全限定镜像名**，避免短名交互：

```bash
docker.io/verdaccio/verdaccio:5
```

如果你的服务器无法稳定访问 DockerHub，建议把 verdaccio 镜像同步到你们的 ACR，再改用 ACR 镜像地址启动。

### 2) DockerHub 拉取超时（包括 warmup 用的 node 镜像）

现象：`i/o timeout`，或镜像站找不到 tag。

解决：

- **不要依赖 DockerHub 的 node 镜像**来跑 warmup，直接用你生产环境的 workspace 镜像（ACR）。
- verdaccio 镜像也建议同步到 ACR（同理）。

### 3) `funai-net` 网络不存在

现象：`unable to find network with name or ID funai-net: network not found`

解决：先手动创建网络（一次性）：

```bash
docker network create funai-net
docker network ls
```

### 4) 调用 `/open-editor` 只会起用户容器，不会自动起 Verdaccio

现象：已经启动 `ws-u-*`，但 `verdaccio` 容器不存在/不可用。

原因：当前实现将 Verdaccio 视为**运维侧常驻基础设施**，后端不会自动拉起 `verdaccio`。

解决：按本文“手动启动/重建命令”先把 Verdaccio 起好；并把老的 `ws-u-*` 容器 `network connect` 进 `funai-net`。

### 5) Verdaccio 启动报 `config.yaml` 非法

现象：`cannot open config file /verdaccio/conf/config.yaml: ... it does not look like a valid config file`

原因：宿主机挂载的 `/data/funai/verdaccio/conf/config.yaml` 内容为空/格式错误。

解决：写入一个**最小可用**配置（示例：上游指向 npmmirror）：

```bash
cat >/data/funai/verdaccio/conf/config.yaml <<'YAML'
storage: /verdaccio/storage

auth:
  htpasswd:
    file: /verdaccio/conf/htpasswd

uplinks:
  npmmirror:
    url: https://registry.npmmirror.com/

packages:
  "@*/*":
    access: $all
    publish: $authenticated
    proxy: npmmirror
  "**":
    access: $all
    publish: $authenticated
    proxy: npmmirror

logs:
  - {type: stdout, format: pretty, level: http}
YAML
```

然后重建容器。

### 6) Verdaccio `storage` 不增长（一直 4K），但 `npm install` 看起来成功

常见原因 A：**权限问题**（最常见，podman/SELinux 环境更容易）

现象：在容器内 `touch /verdaccio/storage/...` 报 `Permission denied`；`/verdaccio/storage` 显示为 `root:root`，verdaccio 进程用户是 `uid=10001`。

你们这次踩到的更直观现象（平台侧常见）：

- `npm create vite@latest ...` / `npm install` 报：`npm error 500 Internal Server Error - GET http://verdaccio:4873/<pkg>`
- Verdaccio 容器日志出现：`EACCES: permission denied, mkdir '/verdaccio/storage/<pkg>'`
- 根因：Verdaccio 无法写入挂载的宿主机 `storage` 目录，导致“上游请求虽成功，但无法落盘缓存”，最终对客户端返回 500。

解决：让宿主机目录对 verdaccio 用户可写（或使用 SELinux 的 `:Z` 挂载）：

```bash
docker rm -f verdaccio 2>/dev/null || true
chown -R 10001:65533 /data/funai/verdaccio/conf /data/funai/verdaccio/storage

docker run -d --name verdaccio --restart=always \
  --network funai-net \
  -v /data/funai/verdaccio/conf:/verdaccio/conf:Z \
  -v /data/funai/verdaccio/storage:/verdaccio/storage:Z \
  docker.io/verdaccio/verdaccio:5
```

若仍不可写（疑似 SELinux），改用：

```bash
-v /data/funai/verdaccio/conf:/verdaccio/conf:Z
-v /data/funai/verdaccio/storage:/verdaccio/storage:Z
```

常见原因 B：**lockfile 的 `resolved` 指向外部 registry，导致绕过 Verdaccio**

现象：`npm ci` 只看到 audit 请求，Verdaccio 日志没有 `.tgz` GET，请求不进入 `storage`。

解决：把 `package-lock.json` 里的 `resolved` 批量改成走 Verdaccio，然后 `npm ci`：

```bash
sed -i 's#https://registry.npmmirror.com/#http://verdaccio:4873/#g' /tmp/npm-warmup/package-lock.json
sed -i 's#https://registry.npmjs.org/#http://verdaccio:4873/#g' /tmp/npm-warmup/package-lock.json

docker run --rm --network funai-net \
  -v /tmp/npm-warmup:/work -w /work \
  <你的workspace镜像> \
  bash -lc "rm -rf node_modules && npm config set registry http://verdaccio:4873 && npm config set audit false && npm ci"
```

### 7) 删除 lockfile 后 `npm install` 报 ERESOLVE（peer 依赖冲突）

现象：`ERESOLVE unable to resolve dependency tree`

原因：删除 lockfile 会触发重新解算依赖树，可能撞 peerDependencies 冲突。

解决：**不要删 lockfile**来做预热；按上面“resolved 替换 + npm ci”即可稳定预热 Verdaccio。

### 8) `npm install` 报 403（Verdaccio/代理/镜像站导致）

现象（容器内）：

- `npm error 403 Forbidden - GET http://verdaccio:4873/@scope%2Fpkg`

排查思路（关键命令）：

1) 看 Verdaccio 是否真的返回 403，还是“上游/uplink 透传”：

```bash
# 跟踪 verdaccio 日志（http level）
podman logs -f verdaccio

# 触发一次查询（在 workspace 容器内）
podman exec -it ws-u-10000021 sh -lc 'npm view @radix-ui/react-dialog --registry http://verdaccio:4873'
```

2) 若 workspace 镜像内没有 curl，可用 node 验证 registry 连通性（不依赖 curl/wget）：

```bash
podman exec -it ws-u-10000021 sh -lc 'node -e "require(\"dns\").lookup(\"verdaccio\",(e,a)=>console.log(e||a))"'
podman exec -it ws-u-10000021 sh -lc 'node -e "require(\"http\").get(\"http://verdaccio:4873/-/ping\",r=>{console.log(r.statusCode);r.resume();}).on(\"error\",e=>console.error(e));"'
```

3) 常见根因：容器内启用了 `HTTP_PROXY/HTTPS_PROXY`，但 `no_proxy/NO_PROXY` 没包含 `verdaccio`，npm 会走代理导致被拦截成 403。

- 解决：确保 `funai.workspace.noProxy` 包含 `verdaccio`（以及必要时其解析 IP），并重建 workspace 容器让 env 生效。

```bash
podman exec -it ws-u-10000021 sh -lc 'env | sort | grep -iE "http_proxy|https_proxy|no_proxy|npm_config_.*proxy"'
```

### 9) `npm install` 报 ERESOLVE（依赖树冲突，npm v7+）

现象：

- `ERESOLVE unable to resolve dependency tree`

可用的止血命令（npm v7+ 常见）：

```bash
npm install --include=dev --legacy-peer-deps
```

说明：

- `--legacy-peer-deps` 会放松 peer 依赖约束，提升“可安装成功率”（但可能隐藏真实冲突，建议后续再按项目要求修正依赖）。
- 在平台“受控 install/build/preview”链路中，建议遇到 ERESOLVE 时自动重试一次 `--legacy-peer-deps`。

### 10) 验证清单（确认缓存已写入 Verdaccio）

```bash
# Verdaccio 是否在跑
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"

# storage 是否增长（预热后通常几十/几百 MB）
du -sh /data/funai/verdaccio/storage

# 日志是否出现包请求（应看到 GET 与 .tgz）
docker logs --tail 200 verdaccio | egrep -i 'GET |tgz' | tail -n 30
```

预热项目固定的依赖配置如下（warmup 项目的 `package.json` 示例）：
{                                                                                                                                                                               "name": "npm-warmup",
  "private": true,
  "version": "1.0.0",
  "dependencies": {
    "@radix-ui/react-dialog": "1.0.5",
    "@radix-ui/react-dropdown-menu": "2.0.6",
    "@radix-ui/react-label": "2.0.2",
    "@radix-ui/react-separator": "1.0.3",
    "@radix-ui/react-slot": "1.0.2",
    "@radix-ui/react-tabs": "1.0.4",
    "@tailwindcss/postcss": "4.1.18",
    "@tailwindcss/vite": "4.0.10",
    "autoprefixer": "10.4.23",
    "axios": "1.13.2",
    "bcryptjs": "3.0.3",
    "class-variance-authority": "0.7.1",
    "clsx": "2.1.1",
    "cors": "2.8.5",
    "date-fns": "2.30.0",
    "dotenv": "16.3.1",
    "express": "4.18.2",
    "jsonwebtoken": "9.0.3",
    "jwt-decode": "4.0.0",
    "lucide-react": "0.475.0",
    "mongoose": "7.8.8",
    "postcss": "8.5.6",
    "prettier": "3.3.3",
    "react": "18.2.0",
    "react-dom": "18.2.0",
    "react-router-dom": "6.22.3",
    "tailwind-merge": "3.4.0",
    "tailwindcss": "4.1.18",
    "typescript": "5.7.2",
    "vite": "6.4.1"
  }
}