# Workspace：internal 子系统（仅内部使用）

## 职责

internal 子系统对外不暴露业务能力，仅提供给 nginx `auth_request` 子请求用，用于把：

- `/ws/{userId}/...` 路径

映射到对应的宿主机端口：

- `127.0.0.1:{hostPort}`

## 接口

- `GET /api/fun-ai/workspace/internal/nginx/port?userId=...`

返回：

- HTTP Header `X-WS-Port: {hostPort}`

安全：

- 优先使用共享密钥校验：Header `X-WS-Token` 或 query `token`
- 若未配置密钥，则退化为仅允许 localhost 访问

关键原则：

- **不做 ensure/start**（无副作用），避免每个静态资源请求都触发容器启动。


