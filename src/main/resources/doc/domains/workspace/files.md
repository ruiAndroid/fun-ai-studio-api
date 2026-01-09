# Workspace：文件域（files 子系统）

## 职责

文件域子系统为在线编辑器提供“文件系统抽象”，所有文件最终落到宿主机持久化目录，并通过 bind mount 让容器可见。

对应控制器：

- `fun.ai.studio.controller.workspace.files.FunAiWorkspaceFileController`
- 路由前缀：`/api/fun-ai/workspace/files`

## 宿主机/容器路径映射

- 宿主机：`{hostRoot}/{userId}/apps/{appId}/...`
- 容器：`/workspace/apps/{appId}/...`（`/workspace` 可配置）

## 接口

- `POST /ensure-dir`：确保应用目录存在（会先 ensure workspace）
- `POST /upload-zip`：上传 zip 解压到 `apps/{appId}`
- `GET /tree`：获取文件树（默认忽略 `node_modules/.git/dist/build/.next/target`）
- `GET /content`：读文件（2MB 文本限制）
- `POST /content`：写文件（支持 `expectedLastModifiedMs` 乐观锁）
- `POST /mkdir`：创建目录
- `POST /delete`：删除文件/目录
- `POST /move`：移动/重命名
- `POST /upload-file`：上传单文件
- `GET /download-file`：下载单文件
- `GET /download-zip`：下载应用目录 zip（可选排除 `node_modules`）

## 关键设计点

- **安全**：所有写操作都必须做 `userId/appId` 归属校验（由 service 层负责）。
- **性能**：文件树接口默认忽略大目录，避免把 `node_modules` 拉爆。
- **一致性**：写文件支持“预期最后修改时间”乐观锁，避免多人/多端覆盖。


