# Workspace：实时通道（realtime 子系统）

实时通道包含两部分：

1) SSE：状态（轻量，给在线编辑器减少轮询）  
2) WebSocket：在线终端（docker exec -i）

## SSE（events）

控制器：

- `fun.ai.studio.controller.workspace.realtime.FunAiWorkspaceRealtimeController`
- 路由：`GET /api/fun-ai/workspace/realtime/events?userId=...&appId=...`
  - 历史兼容参数：`withLog/type`（当前实现不再推送日志，参数会被忽略）

事件：

- `status`：运行态变化时推送（JSON）
- （无事件 keep-alive）：服务端会周期性发送 SSE comment 行保持连接（前端无需处理）
- `error`：异常

关键设计点：

- 先做 `appId` 归属校验，避免被滥用。
- SSE 长连接会周期性触发 `activityTracker.touch(userId)`，避免 idle 回收误伤活跃用户。
- 日志不通过 SSE 增量推送：需要日志时通过 HTTP 拉取（见下文 `log` 接口）。

## 日志（log）

- `GET /api/fun-ai/workspace/realtime/log?userId=...&appId=...&type=BUILD|INSTALL|PREVIEW&tailBytes=0`
  - `tailBytes`: 可选，仅返回末尾 N 字节（大日志快速查看）

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


