# 接口迁移：前端只需要改这些（旧 → 新）

> 只列**发生变化**的接口；没写的就不用改。

---

## A. Workspace 容器级（ensure/status/heartbeat）

- `POST /api/fun-ai/workspace/ensure?userId=...` → `POST /api/fun-ai/workspace/container/ensure?userId=...`
- `GET  /api/fun-ai/workspace/status?userId=...` → `GET  /api/fun-ai/workspace/container/status?userId=...`
- `POST /api/fun-ai/workspace/heartbeat?userId=...` → `POST /api/fun-ai/workspace/container/heartbeat?userId=...`

---

## B. Workspace 文件接口（apps/* → files/*）

- `POST /api/fun-ai/workspace/apps/ensure-dir?userId=...&appId=...` → `POST /api/fun-ai/workspace/files/ensure-dir?userId=...&appId=...`
- `POST /api/fun-ai/workspace/apps/upload?userId=...&appId=...` → `POST /api/fun-ai/workspace/files/upload-zip?userId=...&appId=...`
- `GET  /api/fun-ai/workspace/apps/tree?...` → `GET  /api/fun-ai/workspace/files/tree?...`
- `GET  /api/fun-ai/workspace/apps/file?...` → `GET  /api/fun-ai/workspace/files/content?...`
- `POST /api/fun-ai/workspace/apps/file` → `POST /api/fun-ai/workspace/files/content`
- `POST /api/fun-ai/workspace/apps/mkdir` → `POST /api/fun-ai/workspace/files/mkdir`
- `POST /api/fun-ai/workspace/apps/delete` → `POST /api/fun-ai/workspace/files/delete`
- `POST /api/fun-ai/workspace/apps/move` → `POST /api/fun-ai/workspace/files/move`
- `POST /api/fun-ai/workspace/apps/upload-file?...` → `POST /api/fun-ai/workspace/files/upload-file?...`
- `GET  /api/fun-ai/workspace/apps/download-file?...` → `GET  /api/fun-ai/workspace/files/download-file?...`
- `GET  /api/fun-ai/workspace/apps/download?...` → `GET  /api/fun-ai/workspace/files/download-zip?...`

---

## C. Workspace 实时 SSE（events）

- `GET /api/fun-ai/workspace/events?userId=...&appId=...&withLog=true` → `GET /api/fun-ai/workspace/realtime/events?userId=...&appId=...&withLog=true`



