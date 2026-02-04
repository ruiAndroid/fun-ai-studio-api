# Workspace：npm 依赖安装加速（Verdaccio 代理仓库）

> 现网提示（已更新）：Verdaccio 已统一部署到 **103** 机器，Workspace（87）上的用户容器通过内网访问 `http://172.21.138.103:4873`（或你们内网 DNS 对应的域名）。API（91）侧只需要保持配置与转发链路正确。

## 背景与目标

在线编辑器导入的 Node 项目通常依赖较多。如果每次新项目都在容器内全量 `npm install`：

- 会很慢（重复下载）
- 容器出网不稳定时可能直接失败
- 多人并发时会造成 CPU/IO 峰值

本项目推荐使用 **Verdaccio 代理仓库**作为 npm 依赖安装加速方案：把“出网下载”集中到同机的 registry proxy 上，并缓存到服务端 `storage`，workspace 容器后续安装优先命中缓存。

## 额外说明：npm 本地缓存（~/.npm）可能导致“删项目不回收磁盘”

在容器内执行 `npm install` 时，npm 默认会写本地缓存到 `~/.npm`（例如 `_cacache/_npx`）。如果 workspace 采用“每用户一个长期容器”，那么：

- 即使你删除了某个项目目录（`apps/{appId}`），`~/.npm` 仍在容器可写层里持续增长
- 结果就是：**用户容器磁盘占用越来越大（比如 15GB），删除项目也不下降**

为解决这个问题，平台侧增加了 `funai.workspace.npmCacheMode` 策略（推荐 `APP`）：

- `APP`（推荐）：将 npm cache 放到应用目录内：`{APP_DIR}/.npm-cache`，这样删除项目目录即可回收
- `CONTAINER`：保持默认行为（`~/.npm`），不推荐
- `DISABLED`：将 cache 放到临时目录并在受控任务结束后删除（最省磁盘；有 Verdaccio 时影响较小）

并支持阈值清理（默认 2048MB）：

- `funai.workspace.npmCacheMaxMb=2048`

> 注意：这个策略只影响平台“受控任务”（build/install/preview/dev）。用户在终端里手工执行 npm 仍可能产生其它缓存（可通过运维命令清理）。

## Verdaccio 代理仓库（统一部署在 103）

Verdaccio 的收益：

- **更稳**：弱网时减少大量 registry metadata 请求，失败率更低
- **更快**：首次下载后由 Verdaccio 缓存，后续安装几乎不再出网
- **更可控**：后续可加鉴权/白名单/审计（本项目先按单机最简方式跑通）

### 部署（103：内网常驻基础设施，推荐仅内网访问）

> 重要：Verdaccio 是**运维侧常驻基础设施**。当前后端只负责创建/管理 `ws-u-{userId}` 的 workspace 用户容器，不会自动拉起 `verdaccio` 容器。

#### 手动启动/重建命令（在 103 上执行，可直接复制）

1) 启动 Verdaccio（常驻 + 持久化）：

```bash
mkdir -p /data/funai/verdaccio/{conf,storage}

docker run -d --name verdaccio --restart=always \
  -p 4873:4873 \
  -v /data/funai/verdaccio/conf:/verdaccio/conf:Z \
  -v /data/funai/verdaccio/storage:/verdaccio/storage:Z \
  docker.io/verdaccio/verdaccio:5
```

2) 如需“重建 Verdaccio 容器”（保留数据，仅重建容器）：

```bash
docker rm -f verdaccio 2>/dev/null || true

docker run -d --name verdaccio --restart=always \
  -p 4873:4873 \
  -v /data/funai/verdaccio/conf:/verdaccio/conf:Z \
  -v /data/funai/verdaccio/storage:/verdaccio/storage:Z \
  docker.io/verdaccio/verdaccio:5
```

3) 基本验证（确认 Verdaccio 已启动且可访问）：

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Image}}"
docker logs --tail 50 verdaccio

# 在 103 宿主机验证
curl -I http://127.0.0.1:4873/-/ping || true
```

> 安全建议：4873 **只给内网**（至少仅允许 87/101 等必要机器访问），不要对公网开放。对外统一走 80/443（网关/Nginx/SLB）更安全。

Verdaccio 上游（uplink）建议指向 `https://registry.npmmirror.com`，由 Verdaccio 负责缓存。

---

## 重要提醒：不要把仓库 lockfile 的 `resolved` 写成“内网 Verdaccio 地址”

即便 Verdaccio 统一部署在 103，仍不建议把 `package-lock.json` / `npm-shrinkwrap.json` 的 `resolved` 写成内网地址（例如 `http://172.21.138.103:4873/...` 或内网域名），原因：

- **环境耦合**：离开内网环境（本地开发/临时机/第三方 runner）就会不可构建
- **网络策略差异**：Runner/构建容器是否允许访问 103:4873 往往取决于安全组/路由

因此：

- **建议新生成/新提交的 `package-lock.json` / `npm-shrinkwrap.json` 的 `resolved` 统一使用外网镜像源**
  - 推荐：`https://registry.npmmirror.com/`
  - 或：`https://registry.npmjs.org/`

否则会出现典型故障：Runner 构建阶段 `npm ci` 直接失败，并伴随 `Exit handler never called!`（底层是 resolved 指向的 registry 不可达）。

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

### 后端配置（Spring Boot / workspace-node）

在 workspace-node（87）侧的 `application-prod.properties` 增加/确认（示例使用 103 内网 IP）：

```properties
# Verdaccio（npm 代理仓库）统一部署在 103
funai.workspace.npmRegistry=http://172.21.138.103:4873

# 若容器内启用了 HTTP(S)_PROXY，务必把 verdaccio 地址加入 no_proxy（否则 npm 可能绕代理导致 403/超时）
funai.workspace.noProxy=localhost,127.0.0.1,172.21.138.103,host.containers.internal
```

效果：

- 容器内注入 `NPM_CONFIG_REGISTRY` / `npm_config_registry`
- 运行态脚本会把 **实际 registry** 写到 `run/dev.log`，便于排查

### 验收

- 启动/安装时查看 `run/dev.log`：应包含 `npm registry: http://172.21.138.103:4873`
- Verdaccio 日志可看到首次下载，后续命中缓存

## 预热（建议）：让 Verdaccio 先缓存一批常用依赖

思路：用一个临时 warmup 项目跑一次安装，但确保 registry 指向 Verdaccio，这样缓存会进入 Verdaccio `storage`：

- `npm config set registry http://172.21.138.103:4873`
- `npm install` 或 `npm ci`

> 验收：在 `run/dev.log` 或安装输出中确认 `npm registry` 为 `http://172.21.138.103:4873`。

如果你的 warmup 项目在宿主机目录（例如 `/tmp/npm-warmup`），推荐用一个临时容器来执行预热（执行完自动删除，不占用长期资源）：

```bash
docker run --rm \
  -v /tmp/npm-warmup:/work -w /work \
  <你的workspace镜像> \
  bash -lc "npm config set registry http://172.21.138.103:4873 && (npm ci || npm install)"
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

### 3) 访问 103:4873 超时 / Connection refused

现象：

- `connect ETIMEDOUT 172.21.138.103:4873`
- `Connection refused`

排查/解决：

- 在 87（workspace-node）上验证连通性：
  - `curl -I http://172.21.138.103:4873/-/ping`
- 检查 103 上 Verdaccio 是否在监听：
  - `ss -lntp | grep 4873`
- 检查安全组/防火墙是否放行 **103:4873**（至少允许来源 87/101）

### 4) 调用 `/open-editor` 只会起用户容器，不会自动起 Verdaccio

现象：已经启动 `ws-u-*`，但 `verdaccio` 容器不存在/不可用。

原因：当前实现将 Verdaccio 视为**运维侧常驻基础设施**，后端不会自动拉起 `verdaccio`。

解决：按本文“手动启动/重建命令”先把 103 上的 Verdaccio 起好。

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

- `npm create vite@latest ...` / `npm install` 报：`npm error 500 Internal Server Error - GET http://172.21.138.103:4873/<pkg>`
- Verdaccio 容器日志出现：`EACCES: permission denied, mkdir '/verdaccio/storage/<pkg>'`
- 根因：Verdaccio 无法写入挂载的宿主机 `storage` 目录，导致“上游请求虽成功，但无法落盘缓存”，最终对客户端返回 500。

解决：让宿主机目录对 verdaccio 用户可写（或使用 SELinux 的 `:Z` 挂载）：

```bash
docker rm -f verdaccio 2>/dev/null || true
chown -R 10001:65533 /data/funai/verdaccio/conf /data/funai/verdaccio/storage

docker run -d --name verdaccio --restart=always \
  -p 4873:4873 \
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

解决思路（两种路线）：

#### 路线 1（推荐：兼容 Runner 构建）：保持 lockfile 使用外网镜像源

- 让 `resolved` 保持为 `https://registry.npmmirror.com/`（或 npmjs）
- workspace 侧仍可通过 Verdaccio 做“开发期加速”，但**不要把 lockfile 提交成 `http://172.21.138.103:4873`**

#### 路线 2（仅用于 workspace 容器网络内预热，不要提交到仓库）：把 resolved 临时替换成 Verdaccio

> 适用场景：你只想在 workspace 节点上做一次“缓存预热”，不打算把这个 lockfile 推到 Git 仓库。

把 `package-lock.json` 里的 `resolved` 批量改成走 Verdaccio，然后在容器网络内执行 `npm ci`：

```bash
sed -i 's#https://registry.npmmirror.com/#http://172.21.138.103:4873/#g' /tmp/npm-warmup/package-lock.json
sed -i 's#https://registry.npmjs.org/#http://172.21.138.103:4873/#g' /tmp/npm-warmup/package-lock.json

docker run --rm \
  -v /tmp/npm-warmup:/work -w /work \
  <你的workspace镜像> \
  bash -lc "rm -rf node_modules && npm config set registry http://172.21.138.103:4873 && npm config set audit false && npm ci"
```

### 7) 删除 lockfile 后 `npm install` 报 ERESOLVE（peer 依赖冲突）

现象：`ERESOLVE unable to resolve dependency tree`

原因：删除 lockfile 会触发重新解算依赖树，可能撞 peerDependencies 冲突。

解决：**不要删 lockfile**来做预热；按上面“resolved 替换 + npm ci”即可稳定预热 Verdaccio。

### 8) `npm install` 报 403（Verdaccio/代理/镜像站导致）

现象（容器内）：

- `npm error 403 Forbidden - GET http://172.21.138.103:4873/@scope%2Fpkg`

排查思路（关键命令）：

1) 看 Verdaccio 是否真的返回 403，还是“上游/uplink 透传”：

```bash
# 跟踪 verdaccio 日志（http level）
podman logs -f verdaccio

# 触发一次查询（在 workspace 容器内）
podman exec -it ws-u-10000021 sh -lc 'npm view @radix-ui/react-dialog --registry http://172.21.138.103:4873'
```

2) 若 workspace 镜像内没有 curl，可用 node 验证 registry 连通性（不依赖 curl/wget）：

```bash
podman exec -it ws-u-10000021 sh -lc 'node -e "require(\"dns\").lookup(\"172.21.138.103\",(e,a)=>console.log(e||a))"'
podman exec -it ws-u-10000021 sh -lc 'node -e "require(\"http\").get(\"http://172.21.138.103:4873/-/ping\",r=>{console.log(r.statusCode);r.resume();}).on(\"error\",e=>console.error(e));"'
```

3) 常见根因：容器内启用了 `HTTP_PROXY/HTTPS_PROXY`，但 `no_proxy/NO_PROXY` 没包含 `172.21.138.103`（或对应域名），npm 会走代理导致被拦截成 403。

- 解决：确保 `funai.workspace.noProxy` 包含 `172.21.138.103`（以及必要时对应域名），并重建 workspace 容器让 env 生效。

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