# Workspace 实时通道（在线编辑器）

## 1. SSE：状态/日志推送

### 订阅地址

- `GET /api/fun-ai/workspace/events?userId={userId}&appId={appId}&withLog=true`

### 事件类型

- `status`：JSON 字符串（`Result.success(runStatus)`）
- `log`：纯文本（`dev.log` 增量片段，每秒最多约 32KB）
- `ping`：纯文本时间戳

> 说明：SSE 是单向推送（后端 → 前端），适合推 runState 变化与日志。

---

## 2. WebSocket：在线终端（路径 B：非 TTY）

> 这是“最小可用版终端”，适合 `npm install`、`git`、`ls/cat` 等。  
> 当前 **不分配 TTY**，因此 `vim/top/htop/less` 等全屏交互体验不完整；后续可升级 PTY/TTY。

### 连接地址

- `ws://{host}/api/fun-ai/workspace/ws/terminal?userId={userId}&appId={appId}`
- 如果站点是 HTTPS，请使用 `wss://...`

### Nginx 反代注意事项（否则可能握手 400）

如果你通过 Nginx 反代到 Spring Boot（例如 `location / { proxy_pass http://127.0.0.1:8080; }`），必须**单独**为 WebSocket 路径加上 Upgrade 相关头，并使用 HTTP/1.1 转发，否则后端会把请求当成普通 HTTP GET，从而出现 `Unexpected server response: 400`。

推荐在 `server {}` 里（并且放在 `location /` 之前）增加：

```nginx
# http { } 里放一次即可（用于 Connection upgrade）
map $http_upgrade $connection_upgrade {
  default upgrade;
  ''      close;
}

server {
  # ... 省略 ...

  # WebSocket：/api/fun-ai/workspace/ws/**
  location ^~ /api/fun-ai/workspace/ws/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_buffering off;
  }

  location / {
    proxy_pass http://127.0.0.1:8080;
    # ... 你的其他 header ...
  }
}
```

快速验证（建议先绕过 Nginx 直连后端，定位问题来源）：

- 直连后端：`ws://127.0.0.1:8080/api/fun-ai/workspace/ws/terminal?userId=...&appId=...`
- 再走 Nginx：`ws://{host}/api/fun-ai/workspace/ws/terminal?userId=...&appId=...`

### 出站消息（后端 → 前端）

所有消息都是 JSON 文本：

- `{ "type": "ready", "data": "ok" }`
- `{ "type": "stdout", "data": "..." }`：交互 bash 的输出
- `{ "type": "exec_start", "data": "npm install" }`
- `{ "type": "exec_stdout", "data": "..." }`：exec 任务输出
- `{ "type": "exec_exit", "data": "0" }`：exec 任务退出码
- `{ "type": "exec_cancel", "data": "ok" | "no running job" }`
- `{ "type": "error", "data": "..." }`

### 入站消息（前端 → 后端）

- **交互输入**（写入常驻 bash）：
  - `{ "type": "stdin", "data": "ls\\n" }`

- **任务执行**（建议用于长任务，输出走 exec_stdout）：
  - `{ "type": "exec", "data": "npm install" }`
  - `{ "type": "exec", "data": "npm run dev" }`
  - exec 同时只允许一个任务，重复发会收到 error

- **取消任务**（非 TTY 下的“Ctrl+C”替代方案）：
  - `{ "type": "cancel" }`
  - `{ "type": "ctrl_c" }`：若有 exec 任务则等同 cancel；否则会尝试向 stdin 写入 `^C`（可能无效）

- **resize（占位）**：
  - `{ "type": "resize", "data": "cols=120,rows=30" }`
  - 当前会返回 ignored（非 TTY 模式无法真正 resize）

### 前端最小示例（浏览器）

```js
const ws = new WebSocket(
  `ws://${location.host}/api/fun-ai/workspace/ws/terminal?userId=10000021&appId=20000086`
);

ws.onopen = () => {
  ws.send(JSON.stringify({ type: "exec", data: "pwd" }));
};

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.type === "ready") console.log("terminal ready");
  if (msg.type === "stdout" || msg.type === "exec_stdout") console.log(msg.data);
  if (msg.type === "exec_exit") console.log("exit:", msg.data);
  if (msg.type === "error") console.error(msg.data);
};

function sendInput(text) {
  ws.send(JSON.stringify({ type: "stdin", data: text }));
}
```


