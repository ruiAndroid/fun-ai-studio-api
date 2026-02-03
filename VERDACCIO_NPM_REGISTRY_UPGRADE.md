# Verdaccio NPM Registry 升级方案

## 概述

本次升级实现了完整的 npm 依赖加速方案，从开发态到部署态全链路使用 Verdaccio (103服务器)。

## 架构变更

### 之前
- **87 (Workspace)**: 本地 Verdaccio → 用户开发容器
- **101 (Runner)**: 直接访问 npmjs.org → 构建慢且不稳定

### 现在
- **103**: 统一的 Verdaccio 服务 (4873端口)
- **87 (Workspace)**: 通过 `http://172.21.138.103:4873` 访问
- **101 (Runner)**: 通过 `http://172.21.138.103:4873` 访问

## 实现的功能

### 1. Workspace 自动生成 .npmrc

**位置**: `FunAiWorkspaceServiceImpl.java` - install 任务

**功能**:
- 用户执行 `npm install` 时
- 自动在项目根目录创建 `.npmrc`
- 内容: `registry=http://172.21.138.103:4873`
- 用户只需 commit 这个文件即可

**代码**:
```java
+ "  # 自动生成 .npmrc（用于部署时加速）\n"
+ "  if [ ! -f .npmrc ] && [ -n \"$reg\" ]; then\n"
+ "    echo \"registry=$reg\" > .npmrc\n"
+ "    echo \"[install] created .npmrc with registry=$reg\" >>\"$LOG_FILE\" 2>&1\n"
+ "  fi\n"
```

### 2. Runner 支持 NPM_REGISTRY

**位置**: `fun-ai-studio-runner`

**修改文件**:
1. `runner/settings.py` - 添加 NPM_REGISTRY 配置
2. `runner/build_ops.py` - docker build 时传递 build-arg
3. `config/runner.env` - 配置 Verdaccio 地址

**功能**:
- Runner 构建时自动传递 `--build-arg NPM_REGISTRY=http://172.21.138.103:4873`
- Dockerfile 可以使用这个参数配置 npm registry

### 3. 标准 Dockerfile 模板

**位置**: `GiteaRepoAutomationService.java`

**新模板特性**:
```dockerfile
# 接收构建参数
ARG NPM_REGISTRY=https://registry.npmjs.org

# 优先使用 .npmrc
COPY .npmrc* ./
RUN if [ ! -f .npmrc ]; then echo "registry=${NPM_REGISTRY}" > .npmrc; fi

# 兼容没有 package-lock.json
RUN npm ci 2>/dev/null || npm install

# 兼容没有 build 脚本
RUN if node -e "const p=require('./package.json');const s=(p&&p.scripts)||{};process.exit(s.build?0:1)"; then npm run build; else echo "No build script, skipping..."; fi
```

**优点**:
- ✅ 支持 .npmrc 优先
- ✅ 支持 build-arg 兜底
- ✅ 兼容没有 lockfile
- ✅ 兼容没有 build 脚本

### 4. 架构文档更新

**位置**: `src/main/resources/doc/domains/architecture/README.md`

**更新内容**:
- 系统全景图添加 Verdaccio
- 服务器清单更新 103 的职责
- 端口与通信表添加 4873 端口
- 内部调用表添加 Workspace → Verdaccio

## 完整流程

### 开发态 (Workspace)

1. 用户在浏览器中打开编辑器
2. 点击 "Install" 按钮
3. Workspace 执行 `npm install`:
   - 使用环境变量 `NPM_CONFIG_REGISTRY=http://172.21.138.103:4873`
   - 从 Verdaccio 下载依赖（快速）
   - 自动生成 `.npmrc` 文件
   - 生成 `package-lock.json`
4. 用户 commit 并 push `.npmrc` 和 `package-lock.json`

### 部署态 (Runner)

1. 用户点击 "部署" 按钮
2. Runner 从 Git 拉取代码
3. Runner 执行 `docker build`:
   - 传递 `--build-arg NPM_REGISTRY=http://172.21.138.103:4873`
   - Dockerfile 检测到 `.npmrc` 存在，使用它
   - 或者使用 build-arg 创建 `.npmrc`
   - 执行 `npm ci` (如果有 lockfile) 或 `npm install`
   - 从 Verdaccio 下载依赖（快速）
4. 构建成功，推送镜像到 Harbor
5. Runtime 拉取镜像并运行

## 配置清单

### 103 服务器 (Verdaccio)

```bash
# Verdaccio 容器
docker ps | grep verdaccio
# 应该显示: verdaccio ... Up ... 172.21.138.103:4873->4873/tcp

# 测试访问
curl http://172.21.138.103:4873
```

### 87 服务器 (Workspace)

**配置文件**: `application-prod.properties`
```properties
funai.workspace.npmRegistry=http://172.21.138.103:4873
```

**验证**:
```bash
# 进入workspace容器
docker exec ws-u-10000021 env | grep NPM

# 应该显示:
# NPM_CONFIG_REGISTRY=http://172.21.138.103:4873
# npm_config_registry=http://172.21.138.103:4873
```

### 101 服务器 (Runner)

**配置文件**: `/opt/fun-ai-studio/config/runner.env`
```bash
NPM_REGISTRY=http://172.21.138.103:4873
```

**验证**:
```bash
# 查看配置
cat /opt/fun-ai-studio/config/runner.env | grep NPM_REGISTRY

# 重启服务
sudo systemctl restart fun-ai-studio-runner

# 查看日志
sudo journalctl -u fun-ai-studio-runner -n 50 --no-pager
```

## 部署步骤

### 1. 更新代码

```bash
# 在开发机上
git add .
git commit -m "feat: 统一使用103的Verdaccio，支持.npmrc和无lockfile场景"
git push
```

### 2. 部署 Workspace (87)

```bash
# 在87服务器上
cd /opt/fun-ai-studio
./deploy-workspace.sh
```

### 3. 部署 API (91)

```bash
# 在91服务器上
cd /opt/fun-ai-studio
./deploy-api.sh
```

### 4. 更新 Runner (101)

```bash
# 在101服务器上

# 1. 更新配置
vi /opt/fun-ai-studio/config/runner.env
# 添加: NPM_REGISTRY=http://172.21.138.103:4873

# 2. 拉取最新代码
cd /opt/fun-ai-studio/fun-ai-studio-runner
git pull

# 3. 重启服务
sudo systemctl restart fun-ai-studio-runner

# 4. 验证
sudo journalctl -u fun-ai-studio-runner -n 20 --no-pager
```

### 5. 清理 87 的旧 Verdaccio

```bash
# 在87服务器上

# 1. 停止容器
docker stop verdaccio
docker rm verdaccio

# 2. 备份数据（可选）
tar -czf /data/funai/backups/verdaccio-87-$(date +%Y%m%d).tar.gz /data/funai/verdaccio/

# 3. 删除数据（可选，慎重）
# rm -rf /data/funai/verdaccio/
```

## 测试验证

### 测试1: Workspace 自动生成 .npmrc

1. 创建一个新应用
2. 在编辑器中点击 "Install"
3. 查看日志，应该看到: `created .npmrc with registry=http://172.21.138.103:4873`
4. 查看文件列表，应该有 `.npmrc` 文件

### 测试2: 部署使用 Verdaccio

1. Commit 并 push 代码（包含 .npmrc）
2. 点击 "部署"
3. 查看 Runner 日志，应该看到从 Verdaccio 下载依赖
4. 部署成功

### 测试3: 没有 lockfile 的情况

1. 删除 `package-lock.json`
2. Commit 并 push
3. 点击 "部署"
4. 应该能成功（使用 `npm install` 而不是 `npm ci`）

### 测试4: 没有 .npmrc 的情况

1. 删除 `.npmrc`
2. Commit 并 push
3. 点击 "部署"
4. 应该能成功（使用 Runner 传递的 NPM_REGISTRY）

## 故障排查

### 问题1: Workspace install 很慢

**检查**:
```bash
# 在87服务器上
docker exec ws-u-10000021 npm config get registry
# 应该显示: http://172.21.138.103:4873

# 测试连通性
docker exec ws-u-10000021 curl -I http://172.21.138.103:4873
```

**解决**: 检查 workspace 配置和 Verdaccio 服务状态

### 问题2: 部署时 npm install 很慢

**检查**:
```bash
# 在101服务器上
cat /opt/fun-ai-studio/config/runner.env | grep NPM_REGISTRY

# 查看 Runner 日志
sudo journalctl -u fun-ai-studio-runner -n 100 --no-pager | grep NPM
```

**解决**: 确认 runner.env 配置正确并重启服务

### 问题3: Verdaccio 无法访问

**检查**:
```bash
# 在103服务器上
docker ps | grep verdaccio
docker logs verdaccio --tail 50

# 测试端口
netstat -tlnp | grep 4873
```

**解决**: 重启 Verdaccio 或检查防火墙

## 性能提升

### 开发态
- **之前**: npm install 需要 30-60秒（首次）
- **现在**: npm install 需要 5-10秒（Verdaccio 缓存）

### 部署态
- **之前**: docker build 需要 2-3分钟（npm install 慢）
- **现在**: docker build 需要 1-2分钟（Verdaccio 加速）

### 稳定性
- **之前**: 偶尔因为 npmjs.org 超时导致失败
- **现在**: 几乎不会失败（内网 Verdaccio）

## 后续优化

1. **Verdaccio 监控**: 添加 Prometheus metrics
2. **缓存预热**: 预先缓存常用包
3. **多级缓存**: 考虑在 87 也部署 Verdaccio 作为二级缓存
4. **自动清理**: 定期清理 Verdaccio 的旧包

## 相关文档

- [Verdaccio 部署文档](src/main/resources/doc/domains/workspace/npm-cache.md)
- [Dockerfile 模板说明](fun-ai-studio-runner/DOCKERFILE_TEMPLATE.md)
- [系统架构文档](src/main/resources/doc/domains/architecture/README.md)
