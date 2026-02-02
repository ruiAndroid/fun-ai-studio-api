# Workspace：实时通道（realtime 子系统）

> 双机部署提示：本页接口对外仍由 API 服务器（小机）暴露，但在双机模式下会被 API 服务器（小机）网关/Nginx 转发到 Workspace 开发服务器（大机）容器节点（workspace-node）执行。

实时通道包含：

1) WebSocket：在线终端（docker exec -i）

## WebSocket（在线终端）

处理器：

- `fun.ai.studio.workspace.realtime.WorkspaceTerminalWebSocketHandler`

连接：

- `ws://{host}/api/fun-ai/workspace/ws/terminal?userId=..&appId=..`

入站消息（JSON）：

- `{type:"stdin", data:"ls\n"}`
- `{type:"exec", data:"npm -v"}`
- `{type:"cancel"}`

出站消息（JSON）：

- `{type:"stdout", data:"..."}`
- `{type:"exec_stdout", data:"..."}`
- `{type:"exec_exit", data:"0"}`
- `{type:"ready", data:"ok"}`

约束：

- 当前实现为 **非 TTY**（`docker exec -i`），因此 resize/真实 SIGINT 支持有限。


