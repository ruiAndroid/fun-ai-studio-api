# Workspace：npm 依赖安装加速（Verdaccio 代理仓库）

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

1) 创建网络（让 workspace 容器和 verdaccio 在同一“容器局域网”）：

```bash
docker network create funai-net
```

2) 启动 Verdaccio（容器名固定为 `verdaccio`，加入 `funai-net`）：

```bash
mkdir -p /data/funai/verdaccio/{conf,storage}

docker run -d --name verdaccio --restart=always \
  --network funai-net \
  -v /data/funai/verdaccio/conf:/verdaccio/conf \
  -v /data/funai/verdaccio/storage:/verdaccio/storage \
  verdaccio/verdaccio:5
```

> 说明：本方案面向 **仅容器网络访问**，因此不需要 `-p 4873:4873` 映射到宿主机。若你要给公网/内网访问，需要额外做域名/HTTPS/鉴权/限流，并慎重开放端口。

3) Verdaccio 上游（uplink）建议指向 `https://registry.npmmirror.com`，由 Verdaccio 负责缓存。

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

固定的依赖配置如下（warmup 项目的 `package.json` 示例）：
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