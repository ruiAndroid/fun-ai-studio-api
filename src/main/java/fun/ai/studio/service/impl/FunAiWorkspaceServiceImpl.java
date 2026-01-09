package fun.ai.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileNode;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunMeta;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import fun.ai.studio.entity.FunAiWorkspaceRun;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiWorkspaceRunService;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.CommandResult;
import fun.ai.studio.workspace.CommandRunner;
import fun.ai.studio.workspace.WorkspaceMeta;
import fun.ai.studio.workspace.WorkspaceProperties;
import fun.ai.studio.workspace.ZipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FunAiWorkspaceServiceImpl implements FunAiWorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceServiceImpl.class);

    private final WorkspaceProperties props;
    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final FunAiAppService funAiAppService;
    private final FunAiWorkspaceRunService workspaceRunService;

    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_TEXT_FILE_BYTES = 1024L * 1024L * 2L; // 2MB

    public FunAiWorkspaceServiceImpl(WorkspaceProperties props,
                                     CommandRunner commandRunner,
                                     ObjectMapper objectMapper,
                                     FunAiAppService funAiAppService,
                                     FunAiWorkspaceRunService workspaceRunService) {
        this.props = props;
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
        this.funAiAppService = funAiAppService;
        this.workspaceRunService = workspaceRunService;
    }

    /**
     * 将“最后一次观测/操作结果”写入 DB（单机版：userId 唯一 upsert）。
     * 注意：运行态真相仍以 docker/端口/进程探测为准；DB 仅用于展示/审计/重启恢复体验。
     */
    private void upsertWorkspaceRun(FunAiWorkspaceRun record) {
        if (workspaceRunService == null || record == null || record.getUserId() == null) return;
        try {
            workspaceRunService.upsertByUserId(record);
        } catch (Exception ignore) {
        }
    }

    @Override
    public FunAiWorkspaceInfoResponse ensureWorkspace(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(props.getHostRoot())) {
            throw new IllegalArgumentException("funai.workspace.hostRoot 未配置");
        }
        if (!StringUtils.hasText(props.getImage())) {
            throw new IllegalArgumentException("funai.workspace.image 未配置（建议使用你自己的 ACR 镜像）");
        }

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path appsDir = hostUserDir.resolve("apps");
        Path runDir = hostUserDir.resolve("run");
        ensureDir(hostUserDir);
        ensureDir(appsDir);
        ensureDir(runDir);

        // 全局 npm cache（可选）：跨用户复用依赖下载缓存
        if (props.getNpmCache() != null && props.getNpmCache().isEnabled()) {
            if (!StringUtils.hasText(props.getNpmCache().getHostDir())) {
                throw new IllegalArgumentException("funai.workspace.npmCache.hostDir 未配置");
            }
            ensureDir(Paths.get(props.getNpmCache().getHostDir()));
        }

        // Mongo（可选）：用户级持久化（同一用户容器仅一个 mongod 实例）
        // 目录不应放在 workspace 下（在线编辑器实时同步会干扰 WiredTiger 文件），因此使用单独 hostRoot。
        if (props.getMongo() != null && props.getMongo().isEnabled()) {
            if (!StringUtils.hasText(props.getMongo().getHostRoot())) {
                throw new IllegalArgumentException("funai.workspace.mongo.hostRoot 未配置");
            }
            ensureDir(resolveHostMongoDbDir(userId));
            ensureDir(resolveHostMongoLogDir(userId));
        }

        WorkspaceMeta meta = loadOrInitMeta(userId, hostUserDir);
        ensureContainerRunning(userId, hostUserDir, meta);

        FunAiWorkspaceInfoResponse resp = new FunAiWorkspaceInfoResponse();
        resp.setUserId(userId);
        resp.setContainerName(meta.getContainerName());
        resp.setImage(meta.getImage());
        resp.setHostPort(meta.getHostPort());
        resp.setContainerPort(meta.getContainerPort());
        resp.setHostWorkspaceDir(hostUserDir.toString());
        resp.setHostAppsDir(appsDir.toString());
        resp.setContainerWorkspaceDir(props.getContainerWorkdir());
        resp.setContainerAppsDir(Paths.get(props.getContainerWorkdir(), "apps").toString().replace("\\", "/"));

        // last-known：落库（用于展示/审计/重启后的最后状态）
        FunAiWorkspaceRun r = new FunAiWorkspaceRun();
        r.setUserId(userId);
        r.setContainerName(resp.getContainerName());
        r.setHostPort(resp.getHostPort());
        r.setContainerPort(resp.getContainerPort());
        r.setContainerStatus(queryContainerStatus(resp.getContainerName()));
        upsertWorkspaceRun(r);
        return resp;
    }

    @Override
    public FunAiWorkspaceStatusResponse getStatus(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        WorkspaceMeta meta = tryLoadMeta(hostUserDir);
        String containerName = meta != null ? meta.getContainerName() : containerName(userId);
        Integer hostPort = meta != null ? meta.getHostPort() : null;

        FunAiWorkspaceStatusResponse resp = new FunAiWorkspaceStatusResponse();
        resp.setUserId(userId);
        resp.setContainerName(containerName);
        resp.setHostPort(hostPort);
        resp.setContainerPort(props.getContainerPort());
        resp.setHostWorkspaceDir(hostUserDir.toString());
        String cStatus = queryContainerStatus(containerName);
        resp.setContainerStatus(cStatus);

        // last-known：刷新容器状态（不保证实时真相，仅记录最后观测）
        FunAiWorkspaceRun r = new FunAiWorkspaceRun();
        r.setUserId(userId);
        r.setContainerName(containerName);
        r.setHostPort(hostPort);
        r.setContainerPort(props.getContainerPort());
        r.setContainerStatus(cStatus);
        upsertWorkspaceRun(r);
        return resp;
    }

    @Override
    public FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId) {
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        // 关键：先做 DB 归属校验，再触发容器/目录副作用（ensureWorkspace 会启动容器、创建目录）
        assertAppOwned(userId, appId);
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        Path hostAppDir = resolveHostWorkspaceDir(userId).resolve("apps").resolve(String.valueOf(appId));
        ensureDir(hostAppDir);

        FunAiWorkspaceProjectDirResponse resp = new FunAiWorkspaceProjectDirResponse();
        resp.setUserId(userId);
        resp.setAppId(appId);
        resp.setHostAppDir(hostAppDir.toString());
        resp.setContainerAppDir(Paths.get(ws.getContainerAppsDir(), String.valueOf(appId)).toString().replace("\\", "/"));
        return resp;
    }

    @Override
    public FunAiWorkspaceProjectDirResponse uploadAppZip(Long userId, Long appId, MultipartFile file, boolean overwrite) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("zip文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持上传 .zip 文件");
        }

        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path hostAppDir = Paths.get(dir.getHostAppDir());

        try {
            if (overwrite && Files.exists(hostAppDir)) {
                // 保留应用目录本身，清空内容
                try (var stream = Files.list(hostAppDir)) {
                    stream.forEach(p -> {
                        try {
                            ZipUtils.deleteDirectoryRecursively(p);
                        } catch (Exception ignore) {
                        }
                    });
                }
            } else {
                ensureDir(hostAppDir);
            }

            ZipUtils.unzipSafely(file.getInputStream(), hostAppDir);
            return dir;
        } catch (Exception e) {
            log.error("upload app zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            throw new RuntimeException("上传并解压失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FunAiWorkspaceRunStatusResponse startDev(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        assertAppOwned(userId, appId);

        // 切换模式：同一用户同一时间只能运行一个应用
        // - 若当前已在 RUNNING/STARTING 且 appId 不同：先 stop 再启动目标 app
        // - 若当前已在 RUNNING 且 appId 相同：直接返回当前状态
        try {
            FunAiWorkspaceRunStatusResponse cur = getRunStatus(userId);
            String st = cur == null ? null : cur.getState();
            Long runningAppId = cur == null ? null : cur.getAppId();
            if (runningAppId != null && st != null
                    && ("RUNNING".equalsIgnoreCase(st) || "STARTING".equalsIgnoreCase(st))) {
                if (runningAppId.equals(appId)) {
                    if (cur.getMessage() == null || cur.getMessage().isBlank()) {
                        cur.setMessage("已在运行中");
                    }
                    return cur;
                }
                // stopRun 内部会做进程组 kill + 清理 run 元数据 + 落库
                stopRun(userId);
            }
        } catch (Exception ignore) {
        }

        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        // 确保应用目录存在
        ensureAppDir(userId, appId);

        String containerName = ws.getContainerName();
        String containerAppDir = Paths.get(props.getContainerWorkdir(), "apps", String.valueOf(appId)).toString().replace("\\", "/");
        String runDir = Paths.get(props.getContainerWorkdir(), "run").toString().replace("\\", "/");
        String pidFile = runDir + "/dev.pid";
        String metaFile = runDir + "/current.json";
        String logFile = runDir + "/dev.log";
        String startSh = runDir + "/dev-start.sh";

        // 在容器内启动（只允许一个项目运行：若 pid 仍存活则拒绝）
        // 设计：start 接口不阻塞（不等待 npm install）
        // - 先写入 STARTING 的 meta（pid=null）
        // - 再用后台脚本完成 npm install + 启动 vite，并更新 meta/pid
        String script = ""
                + "set -e\n"
                + "RUN_DIR='" + runDir + "'\n"
                + "PID_FILE='" + pidFile + "'\n"
                + "META_FILE='" + metaFile + "'\n"
                + "LOG_FILE='" + logFile + "'\n"
                + "START_SH='" + startSh + "'\n"
                + "mkdir -p \"$RUN_DIR\"\n"
                + "if [ -f \"$PID_FILE\" ]; then\n"
                + "  pid=$(cat \"$PID_FILE\" 2>/dev/null || true)\n"
                + "  if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then\n"
                + "    echo \"ALREADY_RUNNING:$pid\"\n"
                + "    exit 42\n"
                + "  fi\n"
                + "fi\n"
                + "rm -f \"$PID_FILE\" || true\n"
                + "printf '{\"appId\":" + appId + ",\"type\":\"DEV\",\"pid\":null,\"startedAt\":'\"$(date +%s)\"',\"logPath\":\"" + logFile + "\"}' > \"$META_FILE\"\n"
                // 用单引号 heredoc，避免在“写入脚本文件”这一步把 $APP_DIR/$LOG_FILE 等变量提前展开为空
                + "cat > \"$START_SH\" <<'EOS'\n"
                + "set -e\n"
                + "APP_DIR='" + containerAppDir + "'\n"
                + "PORT='" + ws.getContainerPort() + "'\n"
                + "PID_FILE='" + pidFile + "'\n"
                + "META_FILE='" + metaFile + "'\n"
                + "LOG_FILE='" + logFile + "'\n"
                + (props.getMongo() != null && props.getMongo().isEnabled()
                    ? ""
                    + "export FUNAI_USER_ID='" + userId + "'\n"
                    + "export FUNAI_APP_ID='" + appId + "'\n"
                    + "export MONGO_HOST='127.0.0.1'\n"
                    + "export MONGO_PORT='" + (props.getMongo().getPort() > 0 ? props.getMongo().getPort() : 27017) + "'\n"
                    + "export MONGO_DB_PREFIX='" + (StringUtils.hasText(props.getMongo().getDbNamePrefix()) ? props.getMongo().getDbNamePrefix() : "app_") + "'\n"
                    + "export MONGO_DB_NAME=\"${MONGO_DB_PREFIX}" + appId + "\"\n"
                    + "export MONGO_URL=\"mongodb://${MONGO_HOST}:${MONGO_PORT}/${MONGO_DB_NAME}\"\n"
                    + "export MONGODB_URI=\"$MONGO_URL\"\n"
                    : "")
                + "echo \"[dev-start] start at $(date -Is)\" >>\"$LOG_FILE\" 2>&1\n"
                + "cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1 || true\n"
                + "if [ ! -f package.json ]; then\n"
                + "  # 常见：zip 顶层带一层目录，package.json 在子目录里\n"
                + "  pkg=$(find \"$APP_DIR\" -maxdepth 2 -type f -name package.json 2>/dev/null | head -n 1 || true)\n"
                + "  if [ -n \"$pkg\" ]; then\n"
                + "    APP_DIR=$(dirname \"$pkg\")\n"
                + "    echo \"[dev-start] detected project root: $APP_DIR\" >>\"$LOG_FILE\" 2>&1\n"
                + "    cd \"$APP_DIR\" >>\"$LOG_FILE\" 2>&1\n"
                + "  fi\n"
                + "fi\n"
                + "if [ ! -f package.json ]; then echo \"package.json not found: $APP_DIR\" >>\"$LOG_FILE\"; exit 2; fi\n"
                + "npm config set registry https://registry.npmmirror.com >/dev/null 2>&1 || true\n"
                + "if [ -n \"$NPM_CONFIG_CACHE\" ]; then export npm_config_cache=\"$NPM_CONFIG_CACHE\"; fi\n"
                + "if [ -n \"$npm_config_cache\" ]; then echo \"[dev-start] npm cache: $npm_config_cache\" >>\"$LOG_FILE\" 2>&1; fi\n"
                + "if [ ! -d node_modules ]; then echo \"[dev-start] npm install...\" >>\"$LOG_FILE\"; npm install >>\"$LOG_FILE\" 2>&1; fi\n"
                + "echo \"[dev-start] npm run dev on $PORT\" >>\"$LOG_FILE\" 2>&1\n"
                // 关键：容器 + 挂载卷场景下，inotify 事件可能不稳定，导致“刷新也还是旧内容”（Vite transform 缓存未失效）
                // 通过开启 polling，确保文件增删改能被可靠检测到（代价是稍高 CPU；后续可做成可配置项）
                + "export CHOKIDAR_USEPOLLING=true\n"
                + "export CHOKIDAR_INTERVAL=1000\n"
                + "export WATCHPACK_POLLING=true\n"
                + "echo \"[dev-start] watch: CHOKIDAR_USEPOLLING=$CHOKIDAR_USEPOLLING interval=$CHOKIDAR_INTERVAL\" >>\"$LOG_FILE\" 2>&1\n"
                // 关键：必须确保 dev server 绑定在固定端口（containerPort，默认 5173），因为 nginx/previewUrl 只会反代这个端口
                // 如果端口被历史进程占用，Vite 会自动切到 5174/5175，导致“容器内文件已更新，但 previewUrl 仍旧内容”（命中旧端口）
                // 这里不依赖 ps/lsof/fuser（精简镜像常缺失），直接通过 /proc 定位占用端口的进程并清理
                // /proc/net/tcp 的十六进制端口通常是大写；这里用 toupper 匹配，避免大小写导致找不到 inode
                + "TARGET_PORT_HEX=$(printf '%04X' \"$PORT\")\n"
                + "find_inode() {\n"
                + "  local inode\n"
                + "  inode=$(awk -v p=\":$TARGET_PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp 2>/dev/null || true)\n"
                + "  if [ -z \"$inode\" ]; then\n"
                + "    inode=$(awk -v p=\":$TARGET_PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp6 2>/dev/null || true)\n"
                + "  fi\n"
                + "  echo \"$inode\"\n"
                + "}\n"
                + "kill_by_inode() {\n"
                + "  local inode=\"$1\"\n"
                + "  [ -z \"$inode\" ] && return 0\n"
                + "  echo \"[dev-start] port $PORT is in use (inode=$inode), trying to kill holder...\" >>\"$LOG_FILE\" 2>&1\n"
                + "  for p in /proc/[0-9]*; do\n"
                + "    pid=${p#/proc/}\n"
                + "    for fd in $p/fd/*; do\n"
                + "      link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                + "      if [ \"$link\" = \"socket:[$inode]\" ]; then\n"
                + "        kill -TERM \"$pid\" 2>/dev/null || true\n"
                + "      fi\n"
                + "    done\n"
                + "  done\n"
                + "  sleep 1\n"
                + "  for p in /proc/[0-9]*; do\n"
                + "    pid=${p#/proc/}\n"
                + "    for fd in $p/fd/*; do\n"
                + "      link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                + "      if [ \"$link\" = \"socket:[$inode]\" ]; then\n"
                + "        kill -KILL \"$pid\" 2>/dev/null || true\n"
                + "      fi\n"
                + "    done\n"
                + "  done\n"
                + "}\n"
                + "inode=$(find_inode)\n"
                + "if [ -n \"$inode\" ]; then kill_by_inode \"$inode\"; fi\n"
                // 关键：给 vite 设置 base，使其 dev client/HMR 等资源路径都带上 /ws/{userId}/ 前缀
                // 这样 nginx 才能用路径反代（只开 80/443）而无需做复杂 rewrite/sub_filter
                + "BASE='/ws/" + userId + "/'\n"
                + "setsid sh -c \"npm run dev -- --host 0.0.0.0 --port $PORT --strictPort --base $BASE\" >>\"$LOG_FILE\" 2>&1 < /dev/null &\n"
                + "pid=$!\n"
                + "echo \"$pid\" > \"$PID_FILE\"\n"
                + "printf '{\"appId\":" + appId + ",\"type\":\"DEV\",\"pid\":%s,\"startedAt\":%s,\"logPath\":\"" + logFile + "\"}' \"$pid\" \"$(date +%s)\" > \"$META_FILE\"\n"
                + "EOS\n"
                + "chmod +x \"$START_SH\" || true\n"
                + "nohup bash \"$START_SH\" >>\"$LOG_FILE\" 2>&1 &\n"
                + "echo \"LAUNCHED\"\n";

        CommandResult r = docker("exec", containerName, "bash", "-lc", script);
        if (r.getExitCode() == 42) {
            FunAiWorkspaceRunStatusResponse status = getRunStatus(userId);
            status.setMessage("已在运行中，当前仅允许同时运行一个应用");
            return status;
        }
        if (!r.isSuccess()) {
            throw new RuntimeException("启动 dev 失败: " + r.getOutput());
        }
        FunAiWorkspaceRunStatusResponse status = getRunStatus(userId);
        // start 接口立即返回（一般为 STARTING）
        if (status.getMessage() == null || status.getMessage().isBlank()) {
            status.setMessage("已触发启动，请轮询 /run/status 等待 RUNNING");
        }

        // last-known：记录最近一次 start 的 appId/状态
        FunAiWorkspaceRun r2 = new FunAiWorkspaceRun();
        r2.setUserId(userId);
        r2.setAppId(appId);
        r2.setRunState(status.getState());
        r2.setRunPid(status.getPid());
        r2.setLogPath(status.getLogPath());
        r2.setPreviewUrl(status.getPreviewUrl());
        r2.setLastError(status.getMessage());
        r2.setLastStartedAt(System.currentTimeMillis() / 1000L);
        r2.setContainerName(ws.getContainerName());
        r2.setHostPort(ws.getHostPort());
        r2.setContainerPort(ws.getContainerPort());
        r2.setContainerStatus(queryContainerStatus(ws.getContainerName()));
        upsertWorkspaceRun(r2);
        return status;
    }

    @Override
    public FunAiWorkspaceRunStatusResponse stopRun(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);

        String runDir = Paths.get(props.getContainerWorkdir(), "run").toString().replace("\\", "/");
        String pidFile = runDir + "/dev.pid";
        String metaFile = runDir + "/current.json";

        String script = ""
                + "set -e\n"
                + "RUN_DIR='" + runDir + "'\n"
                + "PID_FILE='" + pidFile + "'\n"
                + "META_FILE='" + metaFile + "'\n"
                + "if [ -f \"$PID_FILE\" ]; then\n"
                + "  pid=$(cat \"$PID_FILE\" 2>/dev/null || true)\n"
                + "  if [ -n \"$pid\" ]; then\n"
                + "    kill -TERM -- -\"$pid\" 2>/dev/null || true\n"
                + "    sleep 1\n"
                + "    kill -KILL -- -\"$pid\" 2>/dev/null || true\n"
                + "  fi\n"
                + "fi\n"
                + "rm -f \"$PID_FILE\" \"$META_FILE\" || true\n"
                + "echo STOPPED\n";

        CommandResult r = docker("exec", ws.getContainerName(), "bash", "-lc", script);
        if (!r.isSuccess()) {
            log.warn("stop run failed: userId={}, out={}", userId, r.getOutput());
        }
        FunAiWorkspaceRunStatusResponse status = getRunStatus(userId);
        // last-known：记录 stop 后状态（通常为 IDLE/DEAD）
        FunAiWorkspaceRun r2 = new FunAiWorkspaceRun();
        r2.setUserId(userId);
        r2.setAppId(status.getAppId());
        r2.setRunState(status.getState());
        r2.setRunPid(status.getPid());
        r2.setLogPath(status.getLogPath());
        r2.setPreviewUrl(status.getPreviewUrl());
        r2.setLastError(status.getMessage());
        r2.setContainerName(ws.getContainerName());
        r2.setHostPort(ws.getHostPort());
        r2.setContainerPort(ws.getContainerPort());
        r2.setContainerStatus(queryContainerStatus(ws.getContainerName()));
        upsertWorkspaceRun(r2);
        return status;
    }

    @Override
    public FunAiWorkspaceRunStatusResponse getRunStatus(Long userId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        Path hostRunDir = resolveHostWorkspaceDir(userId).resolve("run");
        Path metaPath = hostRunDir.resolve("current.json");

        FunAiWorkspaceRunStatusResponse resp = new FunAiWorkspaceRunStatusResponse();
        resp.setUserId(userId);
        resp.setHostPort(ws.getHostPort());
        resp.setContainerPort(ws.getContainerPort());

        if (Files.notExists(metaPath)) {
            resp.setState("IDLE");
            FunAiWorkspaceRun r = new FunAiWorkspaceRun();
            r.setUserId(userId);
            r.setRunState(resp.getState());
            r.setAppId(null);
            r.setRunPid(null);
            r.setPreviewUrl(null);
            r.setLogPath(null);
            r.setLastError(null);
            r.setContainerName(ws.getContainerName());
            r.setHostPort(ws.getHostPort());
            r.setContainerPort(ws.getContainerPort());
            r.setContainerStatus(queryContainerStatus(ws.getContainerName()));
            upsertWorkspaceRun(r);
            return resp;
        }

        try {
            FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
            resp.setAppId(meta.getAppId());
            resp.setPid(meta.getPid());
            resp.setLogPath(meta.getLogPath());

            // pid 为空：说明 start 已触发，但后台脚本尚未写入 pid（npm install 进行中）
            if (meta.getPid() == null) {
                // 先确认容器是否真的在跑：否则会出现“容器已 EXITED，但 run/status 仍一直 STARTING”的假象
                String cStatus = queryContainerStatus(ws.getContainerName());
                if (!"RUNNING".equalsIgnoreCase(cStatus)) {
                    resp.setState("DEAD");
                    resp.setMessage("容器未运行（" + cStatus + "），请先 ensure 再 start；如频繁退出请检查 idle 回收或宿主机资源");
                    FunAiWorkspaceRun r = new FunAiWorkspaceRun();
                    r.setUserId(userId);
                    r.setAppId(resp.getAppId());
                    r.setRunState(resp.getState());
                    r.setRunPid(resp.getPid());
                    r.setPreviewUrl(resp.getPreviewUrl());
                    r.setLogPath(resp.getLogPath());
                    r.setLastError(resp.getMessage());
                    r.setLastStartedAt(meta.getStartedAt());
                    r.setContainerName(ws.getContainerName());
                    r.setHostPort(ws.getHostPort());
                    r.setContainerPort(ws.getContainerPort());
                    r.setContainerStatus(cStatus);
                    upsertWorkspaceRun(r);
                    return resp;
                }

                long nowSec = System.currentTimeMillis() / 1000L;
                long startedAt = meta.getStartedAt() == null ? 0L : meta.getStartedAt();
                int timeoutSec = Math.max(30, props.getRunStartingTimeoutSeconds());
                if (startedAt > 0 && nowSec - startedAt >= timeoutSec) {
                    resp.setState("DEAD");
                    resp.setMessage("启动超时（" + timeoutSec + "s），请查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                } else {
                    resp.setState("STARTING");
                    if (resp.getMessage() == null || resp.getMessage().isBlank()) {
                        resp.setMessage("启动中（可能在 npm install），请稍后重试；可查看日志: " + (resp.getLogPath() == null ? "" : resp.getLogPath()));
                    }
                }
                FunAiWorkspaceRun r = new FunAiWorkspaceRun();
                r.setUserId(userId);
                r.setAppId(resp.getAppId());
                r.setRunState(resp.getState());
                r.setRunPid(resp.getPid());
                r.setPreviewUrl(resp.getPreviewUrl());
                r.setLogPath(resp.getLogPath());
                r.setLastError(resp.getMessage());
                r.setLastStartedAt(meta.getStartedAt());
                r.setContainerName(ws.getContainerName());
                r.setHostPort(ws.getHostPort());
                r.setContainerPort(ws.getContainerPort());
                r.setContainerStatus(queryContainerStatus(ws.getContainerName()));
                upsertWorkspaceRun(r);
                return resp;
            }

            // 进程存活校验（容器内） + 端口就绪判定（/dev/tcp，无需依赖 curl/ss）
            String check = ""
                    + "pid=" + meta.getPid() + "\n"
                    + "port=" + ws.getContainerPort() + "\n"
                    + "if kill -0 \"$pid\" 2>/dev/null; then\n"
                    + "  (echo > /dev/tcp/127.0.0.1/$port) >/dev/null 2>&1 && echo RUNNING || echo STARTING\n"
                    + "else\n"
                    + "  echo DEAD\n"
                    + "fi\n";
            CommandResult alive = docker("exec", ws.getContainerName(), "bash", "-lc", check);
            String s = alive.getOutput() == null ? "" : alive.getOutput().trim();
            if (s.contains("RUNNING")) {
                resp.setState("RUNNING");
                resp.setPreviewUrl(buildPreviewUrl(ws));
            } else if (s.contains("STARTING")) {
                resp.setState("STARTING");
            } else {
                resp.setState("DEAD");
            }

            // 诊断：containerPort 当前监听进程 pid（用于排查端口被旧进程占用，导致 previewUrl 指向旧内容）
            Long listenPid = tryGetListenPid(ws.getContainerName(), ws.getContainerPort());
            resp.setPortListenPid(listenPid);
            // 注意：current.json 的 pid 是 setsid/sh 的“进程组组长”，真正监听端口的通常是 node 子进程
            // 因此这里用“端口监听进程的 pgrp 是否等于 current.json pid”来判断是否同一次 run
            if (listenPid != null && resp.getPid() != null) {
                Long listenPgrp = tryGetProcessGroupId(ws.getContainerName(), listenPid);
                if (listenPgrp != null && !listenPgrp.equals(resp.getPid())) {
                    String warn = "诊断：containerPort=" + ws.getContainerPort()
                            + " 当前监听 pid=" + listenPid + "(pgrp=" + listenPgrp + ")，但 current.json pid(pgrp leader)=" + resp.getPid()
                            + "；预览可能命中旧进程/旧项目。建议先 stopRun 再 startDev。";
                    if (resp.getMessage() == null || resp.getMessage().isBlank()) resp.setMessage(warn);
                    else resp.setMessage(resp.getMessage() + "；" + warn);
                }
            }

            FunAiWorkspaceRun r = new FunAiWorkspaceRun();
            r.setUserId(userId);
            r.setAppId(resp.getAppId());
            r.setRunState(resp.getState());
            r.setRunPid(resp.getPid());
            r.setPreviewUrl(resp.getPreviewUrl());
            r.setLogPath(resp.getLogPath());
            r.setLastError(resp.getMessage());
            r.setLastStartedAt(meta.getStartedAt());
            r.setContainerName(ws.getContainerName());
            r.setHostPort(ws.getHostPort());
            r.setContainerPort(ws.getContainerPort());
            r.setContainerStatus(queryContainerStatus(ws.getContainerName()));
            upsertWorkspaceRun(r);
            return resp;
        } catch (Exception e) {
            log.warn("read run meta failed: userId={}, error={}", userId, e.getMessage());
            resp.setState("UNKNOWN");
            resp.setMessage("读取运行状态失败: " + e.getMessage());
            FunAiWorkspaceRun r = new FunAiWorkspaceRun();
            r.setUserId(userId);
            r.setRunState(resp.getState());
            r.setLastError(resp.getMessage());
            r.setContainerName(ws.getContainerName());
            r.setHostPort(ws.getHostPort());
            r.setContainerPort(ws.getContainerPort());
            r.setContainerStatus(queryContainerStatus(ws.getContainerName()));
            upsertWorkspaceRun(r);
            return resp;
        }
    }

    /**
     * 查询容器内指定端口（LISTEN）对应的 pid。
     * 不依赖 ps/ss/lsof（精简镜像常缺失），通过 /proc/net/tcp(+tcp6) + /proc/<pid>/fd 反查 socket inode。
     */
    private Long tryGetListenPid(String containerName, Integer port) {
        if (containerName == null || containerName.isBlank() || port == null) return null;
        try {
            String script = ""
                    + "set -e\n"
                    + "port=" + port + "\n"
                    + "PORT_HEX=$(printf '%04X' \"$port\")\n"
                    + "inode=$(awk -v p=\":$PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp 2>/dev/null || true)\n"
                    + "if [ -z \"$inode\" ]; then inode=$(awk -v p=\":$PORT_HEX\" 'toupper($2) ~ (p\"$\") && $4==\"0A\" {print $10; exit}' /proc/net/tcp6 2>/dev/null || true); fi\n"
                    + "[ -z \"$inode\" ] && exit 0\n"
                    + "for p in /proc/[0-9]*; do\n"
                    + "  pid=${p#/proc/}\n"
                    + "  for fd in $p/fd/*; do\n"
                    + "    link=$(readlink \"$fd\" 2>/dev/null || true)\n"
                    + "    if [ \"$link\" = \"socket:[$inode]\" ]; then\n"
                    + "      echo \"$pid\"\n"
                    + "      exit 0\n"
                    + "    fi\n"
                    + "  done\n"
                    + "done\n"
                    + "exit 0\n";
            CommandResult r = docker("exec", containerName, "bash", "-lc", script);
            if (r == null || r.getOutput() == null) return null;
            // podman-docker 可能在 stdout 打印告警，这里取最后一个非空行
            String out = normalizeDockerCliOutput(r.getOutput());
            if (out.isBlank()) return null;
            if (!out.matches("\\d+")) return null;
            return Long.parseLong(out);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long tryGetProcessGroupId(String containerName, Long pid) {
        if (containerName == null || containerName.isBlank() || pid == null || pid <= 0) return null;
        try {
            String script = ""
                    + "pid=" + pid + "\n"
                    + "awk '{print $5}' /proc/$pid/stat 2>/dev/null || true\n";
            CommandResult r = docker("exec", containerName, "bash", "-lc", script);
            if (r == null || r.getOutput() == null) return null;
            String out = normalizeDockerCliOutput(r.getOutput());
            if (out.isBlank()) return null;
            if (!out.matches("\\d+")) return null;
            return Long.parseLong(out);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String buildPreviewUrl(FunAiWorkspaceInfoResponse ws) {
        String base = props.getPreviewBaseUrl();
        if (base == null) return null;
        base = base.trim();
        if (base.isEmpty()) return null;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.contains("://")) {
            base = "http://" + base;
        }
        String prefix = props.getPreviewPathPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "/ws";
        }
        prefix = prefix.trim();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return base + prefix + "/" + ws.getUserId() + "/";
    }

    @Override
    public Path exportAppAsZip(Long userId, Long appId) {
        assertEnabled();
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        assertAppOwned(userId, appId);

        // 确保 workspace 与 app 目录存在
        ensureWorkspace(userId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path hostAppDir = Paths.get(dir.getHostAppDir());
        if (Files.notExists(hostAppDir) || !Files.isDirectory(hostAppDir)) {
            throw new IllegalArgumentException("应用目录不存在: " + hostAppDir);
        }

        Path runDir = resolveHostWorkspaceDir(userId).resolve("run");
        ensureDir(runDir);

        String fileName = "app_" + appId + "_" + System.currentTimeMillis() + ".zip";
        Path zipPath = runDir.resolve(fileName);

        try (OutputStream os = Files.newOutputStream(zipPath, StandardOpenOption.CREATE_NEW)) {
            ZipUtils.zipDirectory(hostAppDir, os);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(zipPath);
            } catch (Exception ignore) {
            }
            throw new RuntimeException("打包失败: " + e.getMessage(), e);
        }
        return zipPath;
    }

    @Override
    public FunAiWorkspaceFileTreeResponse listFileTree(Long userId, Long appId, String path, Integer maxDepth, Integer maxEntries) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());

        int depth = maxDepth == null ? 6 : Math.max(0, Math.min(20, maxDepth));
        int limit = maxEntries == null ? 5000 : Math.max(1, Math.min(20000, maxEntries));

        Path start = resolveSafePath(root, path, true);
        if (Files.notExists(start)) {
            // 空目录树
            FunAiWorkspaceFileTreeResponse resp = new FunAiWorkspaceFileTreeResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setRootPath(normalizeRelPath(root, start));
            resp.setMaxDepth(depth);
            resp.setMaxEntries(limit);
            resp.setNodes(List.of());
            return resp;
        }
        if (!Files.isDirectory(start)) {
            throw new IllegalArgumentException("不是目录: " + normalizeRelPath(root, start));
        }

        Set<String> ignores = defaultIgnoredNames();
        Counter counter = new Counter(limit);
        List<FunAiWorkspaceFileNode> nodes = listDirRecursive(root, start, depth, ignores, counter);

        FunAiWorkspaceFileTreeResponse resp = new FunAiWorkspaceFileTreeResponse();
        resp.setUserId(userId);
        resp.setAppId(appId);
        resp.setRootPath(normalizeRelPath(root, start));
        resp.setMaxDepth(depth);
        resp.setMaxEntries(limit);
        resp.setNodes(nodes);
        return resp;
    }

    @Override
    public FunAiWorkspaceFileReadResponse readFileContent(Long userId, Long appId, String path) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path file = resolveSafePath(root, path, false);
        if (Files.notExists(file) || Files.isDirectory(file)) {
            throw new IllegalArgumentException("文件不存在: " + normalizeRelPath(root, file));
        }
        try {
            long size = Files.size(file);
            if (size > MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("文件过大（" + size + " bytes），暂不支持在线读取");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            FunAiWorkspaceFileReadResponse resp = new FunAiWorkspaceFileReadResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setPath(normalizeRelPath(root, file));
            resp.setContent(content);
            resp.setSize(size);
            resp.setLastModifiedMs(Files.getLastModifiedTime(file).toMillis());
            return resp;
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FunAiWorkspaceFileReadResponse writeFileContent(Long userId, Long appId, String path, String content, boolean createParents, Long expectedLastModifiedMs) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path file = resolveSafePath(root, path, false);

        if (Files.exists(file) && Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录，无法写入文件: " + normalizeRelPath(root, file));
        }

        try {
            if (createParents) {
                Path parent = file.getParent();
                if (parent != null) ensureDir(parent);
            }

            // optimistic lock
            if (expectedLastModifiedMs != null) {
                if (Files.exists(file)) {
                    long current = Files.getLastModifiedTime(file).toMillis();
                    if (current != expectedLastModifiedMs) {
                        throw new IllegalStateException("文件已被其他人修改（currentLastModifiedMs=" + current + "），请先重新拉取再保存");
                    }
                } else {
                    // must not exist
                    if (!(expectedLastModifiedMs == 0L || expectedLastModifiedMs == -1L)) {
                        throw new IllegalStateException("文件不存在，expectedLastModifiedMs=" + expectedLastModifiedMs + " 不匹配（可传 0/-1 表示必须不存在）");
                    }
                }
            }

            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_FILE_BYTES) {
                throw new IllegalArgumentException("内容过大（" + bytes.length + " bytes），请分拆或改为上传文件");
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return readFileContent(userId, appId, normalizeRelPath(root, file));
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void createDirectory(Long userId, Long appId, String path, boolean createParents) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path d = resolveSafePath(root, path, false);
        try {
            if (Files.exists(d) && !Files.isDirectory(d)) {
                throw new IllegalArgumentException("路径已存在且不是目录: " + normalizeRelPath(root, d));
            }
            if (createParents) {
                Files.createDirectories(d);
            } else {
                Files.createDirectory(d);
            }
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deletePath(Long userId, Long appId, String path) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path target = resolveSafePath(root, path, false);
        if (target.normalize().equals(root.normalize())) {
            throw new IllegalArgumentException("禁止删除 app 根目录");
        }
        if (Files.notExists(target)) return;
        try {
            ZipUtils.deleteDirectoryRecursively(target);
        } catch (Exception e) {
            throw new RuntimeException("删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void movePath(Long userId, Long appId, String fromPath, String toPath, boolean overwrite) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());

        Path from = resolveSafePath(root, fromPath, false);
        Path to = resolveSafePath(root, toPath, false);
        if (Files.notExists(from)) {
            throw new IllegalArgumentException("源路径不存在: " + normalizeRelPath(root, from));
        }
        if (to.normalize().equals(root.normalize())) {
            throw new IllegalArgumentException("目标路径非法");
        }
        try {
            Path parent = to.getParent();
            if (parent != null) ensureDir(parent);
            if (Files.exists(to)) {
                if (!overwrite) {
                    throw new IllegalArgumentException("目标已存在: " + normalizeRelPath(root, to));
                }
                ZipUtils.deleteDirectoryRecursively(to);
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("移动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FunAiWorkspaceFileReadResponse uploadFile(Long userId, Long appId, String path, MultipartFile file, boolean overwrite, boolean createParents) {
        assertEnabled();
        assertAppOwned(userId, appId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path target = resolveSafePath(root, path, false);
        try {
            if (Files.exists(target) && Files.isDirectory(target)) {
                throw new IllegalArgumentException("目标是目录: " + normalizeRelPath(root, target));
            }
            if (Files.exists(target) && !overwrite) {
                throw new IllegalArgumentException("目标已存在: " + normalizeRelPath(root, target));
            }
            if (createParents) {
                Path parent = target.getParent();
                if (parent != null) ensureDir(parent);
            }
            try (var is = file.getInputStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return readFileContent(userId, appId, normalizeRelPath(root, target));
        } catch (IOException e) {
            throw new RuntimeException("上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Path downloadFile(Long userId, Long appId, String path) {
        assertEnabled();
        assertAppOwned(userId, appId);
        FunAiWorkspaceProjectDirResponse dir = ensureAppDir(userId, appId);
        Path root = Paths.get(dir.getHostAppDir());
        Path file = resolveSafePath(root, path, false);
        if (Files.notExists(file) || Files.isDirectory(file)) {
            throw new IllegalArgumentException("文件不存在: " + normalizeRelPath(root, file));
        }
        return file;
    }

    private static class Counter {
        int limit;
        int used;
        Counter(int limit) { this.limit = limit; }
        boolean inc() { used++; return used <= limit; }
        boolean allow() { return used < limit; }
    }

    private Set<String> defaultIgnoredNames() {
        Set<String> s = new HashSet<>();
        s.add("node_modules");
        s.add(".git");
        s.add("dist");
        s.add("build");
        s.add(".next");
        s.add("target");
        return s;
    }

    private List<FunAiWorkspaceFileNode> listDirRecursive(Path root, Path dir, int depth, Set<String> ignores, Counter counter) {
        if (depth < 0 || !counter.allow()) return List.of();
        try (var stream = Files.list(dir)) {
            List<Path> children = stream
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();

            List<FunAiWorkspaceFileNode> out = new ArrayList<>();
            for (Path p : children) {
                if (!counter.allow()) break;
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (ignores.contains(name)) continue;

                FunAiWorkspaceFileNode n = new FunAiWorkspaceFileNode();
                n.setName(name);
                n.setPath(normalizeRelPath(root, p));
                n.setLastModifiedMs(safeLastModifiedMs(p));
                if (Files.isDirectory(p)) {
                    n.setType("DIR");
                    if (counter.inc() && depth > 0) {
                        n.setChildren(listDirRecursive(root, p, depth - 1, ignores, counter));
                    } else {
                        n.setChildren(List.of());
                    }
                } else {
                    n.setType("FILE");
                    n.setSize(safeSize(p));
                    counter.inc();
                }
                out.add(n);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败: " + e.getMessage(), e);
        }
    }

    private Long safeLastModifiedMs(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Path resolveSafePath(Path root, String rel, boolean allowEmpty) {
        if (root == null) throw new IllegalArgumentException("root 不能为空");
        String r = rel == null ? "" : rel.trim();
        r = r.replace("\\", "/");
        while (r.startsWith("/")) r = r.substring(1);
        if (!allowEmpty && (r.isEmpty() || ".".equals(r))) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (r.contains("\0")) {
            throw new IllegalArgumentException("非法 path");
        }
        Path resolved = r.isEmpty() ? root : root.resolve(r);
        resolved = resolved.normalize();
        Path normalizedRoot = root.normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法 path（疑似越权访问）");
        }
        return resolved;
    }

    private String normalizeRelPath(Path root, Path p) {
        try {
            Path rp = root.normalize().relativize(p.normalize());
            String s = rp.toString().replace("\\", "/");
            return s.isEmpty() ? "." : s;
        } catch (Exception ignore) {
            return ".";
        }
    }

    @Override
    public void stopRunForIdle(Long userId) {
        if (userId == null) return;
        if (!props.isEnabled()) return;

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path runDir = hostUserDir.resolve("run");
        Path pidPath = runDir.resolve("dev.pid");
        Path metaPath = runDir.resolve("current.json");
        if (Files.notExists(pidPath) && Files.notExists(metaPath)) {
            return;
        }

        String containerName = containerNameFromMetaOrDefault(hostUserDir, userId);
        String status = queryContainerStatus(containerName);
        Long pid = readPidFromRunFiles(pidPath, metaPath);

        try {
            // 若容器还在跑且 pid 可用，则尝试 kill；否则只清理 run 文件即可
            if ("RUNNING".equalsIgnoreCase(status) && pid != null && pid > 1) {
                docker("exec", containerName, "bash", "-lc",
                        "kill -TERM -- -" + pid + " 2>/dev/null || true; sleep 1; kill -KILL -- -" + pid + " 2>/dev/null || true");
            }
        } catch (Exception ignore) {
        } finally {
            try {
                Files.deleteIfExists(pidPath);
                Files.deleteIfExists(metaPath);
                Files.deleteIfExists(runDir.resolve("dev.log"));
            } catch (Exception ignore) {
            }
        }

        // last-known：idle stop run 后记录为 IDLE
        FunAiWorkspaceRun r = new FunAiWorkspaceRun();
        r.setUserId(userId);
        r.setRunState("IDLE");
        r.setRunPid(null);
        r.setPreviewUrl(null);
        r.setLogPath(null);
        r.setLastError("idle reaper stopped run");
        r.setContainerName(containerName);
        r.setContainerStatus(queryContainerStatus(containerName));
        upsertWorkspaceRun(r);
    }

    @Override
    public void stopContainerForIdle(Long userId) {
        if (userId == null) return;
        if (!props.isEnabled()) return;

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        String containerName = containerNameFromMetaOrDefault(hostUserDir, userId);
        String status = queryContainerStatus(containerName);
        if (!"RUNNING".equalsIgnoreCase(status)) {
            return;
        }
        try {
            docker("stop", containerName);
        } catch (Exception ignore) {
        }

        // last-known：idle stop container 后刷新容器状态
        FunAiWorkspaceRun r = new FunAiWorkspaceRun();
        r.setUserId(userId);
        r.setContainerName(containerName);
        r.setContainerStatus(queryContainerStatus(containerName));
        upsertWorkspaceRun(r);
    }

    @Override
    public void cleanupWorkspaceOnAppDeleted(Long userId, Long appId) {
        // 注意：此方法不做 DB 归属校验；由调用方（删除应用接口）保证已校验 app 归属
        if (userId == null || appId == null) return;
        if (props == null || !props.isEnabled()) return;
        if (!StringUtils.hasText(props.getHostRoot())) return;

        Path hostUserDir = resolveHostWorkspaceDir(userId);
        Path hostAppsDir = hostUserDir.resolve("apps");
        Path hostAppDir = hostAppsDir.resolve(String.valueOf(appId));
        Path hostRunDir = hostUserDir.resolve("run");
        Path metaPath = hostRunDir.resolve("current.json");
        Path pidPath = hostRunDir.resolve("dev.pid");

        // 1) 如果删除的是当前运行 app：尽量 stopRun（仅当容器在 RUNNING）
        try {
            if (Files.exists(metaPath)) {
                FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
                if (meta != null && meta.getAppId() != null && meta.getAppId().equals(appId)) {
                    String containerName = containerName(userId);
                    String cStatus = queryContainerStatus(containerName);
                    if ("RUNNING".equalsIgnoreCase(cStatus)) {
                        // 容器在跑：通过 stopRun 杀掉进程组并清理 run 文件（会落库）
                        stopRun(userId);
                    } else {
                        // 容器不在跑：仅清理宿主机 run 元数据，并落库为 IDLE
                        try {
                            Files.deleteIfExists(pidPath);
                            Files.deleteIfExists(metaPath);
                        } catch (Exception ignore) {
                        }
                        FunAiWorkspaceRun r = new FunAiWorkspaceRun();
                        r.setUserId(userId);
                        r.setAppId(null);
                        r.setRunState("IDLE");
                        r.setRunPid(null);
                        r.setPreviewUrl(null);
                        r.setLogPath(null);
                        r.setLastError("app deleted: " + appId);
                        r.setContainerName(containerName);
                        r.setHostPort(getHostPortForNginx(userId));
                        r.setContainerPort(props.getContainerPort());
                        r.setContainerStatus(cStatus);
                        upsertWorkspaceRun(r);
                    }
                }
            }
        } catch (Exception e) {
            // 清理失败不阻断删除流程
            log.warn("cleanup workspace run meta failed: userId={}, appId={}, err={}", userId, appId, e.getMessage());
        }

        // 2) 删除 workspace app 目录（宿主机持久化）
        try {
            if (Files.exists(hostAppDir)) {
                ZipUtils.deleteDirectoryRecursively(hostAppDir);
            }
        } catch (Exception e) {
            log.warn("cleanup workspace app dir failed: userId={}, appId={}, dir={}, err={}",
                    userId, appId, hostAppDir, e.getMessage());
        }
    }

    private void assertEnabled() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("workspace 功能未启用（funai.workspace.enabled=false）");
        }
    }

    private void assertAppOwned(Long userId, Long appId) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        // 归属校验：appId 必须属于 userId
        if (funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }
    }

    private String containerNameFromMetaOrDefault(Path hostUserDir, Long userId) {
        try {
            WorkspaceMeta meta = tryLoadMeta(hostUserDir);
            if (meta != null && StringUtils.hasText(meta.getContainerName())) {
                return meta.getContainerName();
            }
        } catch (Exception ignore) {
        }
        return containerName(userId);
    }

    private Long readPidFromRunFiles(Path pidPath, Path metaPath) {
        try {
            if (Files.exists(metaPath)) {
                FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
                if (meta != null && meta.getPid() != null) {
                    return meta.getPid();
                }
            }
        } catch (Exception ignore) {
        }
        try {
            if (Files.exists(pidPath)) {
                String s = Files.readString(pidPath, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return Long.parseLong(s);
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String sanitizePath(String p) {
        if (p == null) return null;
        return p.trim().replaceAll("^[\"']|[\"']$", "");
    }

    private Path resolveHostWorkspaceDir(Long userId) {
        String root = sanitizePath(props.getHostRoot());
        return Paths.get(root, String.valueOf(userId));
    }

    private void ensureDir(Path dir) {
        try {
            if (Files.notExists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + dir + ", error=" + e.getMessage(), e);
        }
    }

    private Path metaFile(Path hostUserDir) {
        return hostUserDir.resolve("workspace-meta.json");
    }

    private WorkspaceMeta tryLoadMeta(Path hostUserDir) {
        Path f = metaFile(hostUserDir);
        if (Files.notExists(f)) return null;
        try {
            return objectMapper.readValue(Files.readAllBytes(f), WorkspaceMeta.class);
        } catch (Exception e) {
            log.warn("读取 workspace meta 失败（将忽略并重建）: file={}, error={}", f, e.getMessage());
            return null;
        }
    }

    private WorkspaceMeta loadOrInitMeta(Long userId, Path hostUserDir) {
        WorkspaceMeta meta = tryLoadMeta(hostUserDir);
        if (meta != null && meta.getHostPort() != null && meta.getContainerPort() != null && StringUtils.hasText(meta.getContainerName())) {
            return meta;
        }

        WorkspaceMeta m = new WorkspaceMeta();
        m.setContainerPort(props.getContainerPort());
        m.setHostPort(allocateHostPort(userId));
        m.setImage(props.getImage());
        m.setContainerName(containerName(userId));
        m.setCreatedAt(System.currentTimeMillis());
        persistMeta(hostUserDir, m);
        return m;
    }

    /**
     * 仅用于 nginx auth_request：读取 workspace-meta.json 中的 hostPort（不做 ensure/start，避免每个静态资源请求都触发副作用）
     */
    public Integer getHostPortForNginx(Long userId) {
        if (userId == null) return null;
        Path hostUserDir = resolveHostWorkspaceDir(userId);
        WorkspaceMeta meta = tryLoadMeta(hostUserDir);
        if (meta == null) return null;
        return meta.getHostPort();
    }

    private void persistMeta(Path hostUserDir, WorkspaceMeta meta) {
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta);
            Files.write(metaFile(hostUserDir), bytes);
        } catch (Exception e) {
            log.warn("写入 workspace meta 失败（不影响后续流程）: dir={}, error={}", hostUserDir, e.getMessage());
        }
    }

    private String containerName(Long userId) {
        return props.getContainerNamePrefix() + userId;
    }

    private int allocateHostPort(Long userId) {
        int base = props.getHostPortBase();
        int scan = Math.max(1, props.getHostPortScanSize());
        int startOffset = (int) (Math.abs(userId) % scan);

        for (int i = 0; i < scan; i++) {
            int p = base + ((startOffset + i) % scan);
            if (isPortFree(p)) {
                return p;
            }
        }
        throw new IllegalStateException("无法分配可用端口（范围不足）：base=" + base + ", scan=" + scan);
    }

    private boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException ignore) {
            return false;
        }
    }

    private void ensureContainerRunning(Long userId, Path hostUserDir, WorkspaceMeta meta) {
        String name = meta.getContainerName();

        String status = queryContainerStatus(name);
        if ("RUNNING".equalsIgnoreCase(status)) {
            return;
        }
        if (!"NOT_CREATED".equalsIgnoreCase(status)) {
            // 容器存在但非 running：尝试启动
            CommandResult start = docker("start", name);
            if (start.isSuccess()) {
                return;
            }
            log.warn("docker start failed: userId={}, name={}, out={}", userId, name, start.getOutput());
        }

        // 不存在：创建并启动（长驻）
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(name);
        cmd.add("--restart=always");
        cmd.add("-p");
        cmd.add(meta.getHostPort() + ":" + meta.getContainerPort());
        cmd.add("-v");
        cmd.add(hostUserDir.toString() + ":" + props.getContainerWorkdir());
        cmd.add("-w");
        cmd.add(props.getContainerWorkdir());

        // npm cache bind mount（可选）
        if (props.getNpmCache() != null && props.getNpmCache().isEnabled()) {
            String hostDir = props.getNpmCache().getHostDir();
            String containerDir = StringUtils.hasText(props.getNpmCache().getContainerDir())
                    ? props.getNpmCache().getContainerDir()
                    : "/opt/funai/npm-cache";

            cmd.add("-v");
            cmd.add(hostDir + ":" + containerDir);

            cmd.add("-e");
            cmd.add("NPM_CONFIG_CACHE=" + containerDir);
            cmd.add("-e");
            cmd.add("npm_config_cache=" + containerDir);
            cmd.add("-e");
            cmd.add("npm_config_prefer_offline=" + props.getNpmCache().isPreferOffline());
            cmd.add("-e");
            cmd.add("npm_config_fetch_retries=" + Math.max(0, props.getNpmCache().getFetchRetries()));
            cmd.add("-e");
            cmd.add("npm_config_fetch_timeout=" + Math.max(10_000, props.getNpmCache().getFetchTimeoutMs()));
        }

        // mongo bind mount（可选）
        if (props.getMongo() != null && props.getMongo().isEnabled()) {
            Path hostMongoDb = resolveHostMongoDbDir(userId);
            Path hostMongoLog = resolveHostMongoLogDir(userId);
            cmd.add("-v");
            cmd.add(hostMongoDb.toString() + ":" + props.getMongo().getContainerDbPath());
            cmd.add("-v");
            cmd.add(hostMongoLog.toString() + ":" + props.getMongo().getContainerLogDir());

            // 注入连接信息（Node 侧按 appId 选择 dbName）
            cmd.add("-e");
            cmd.add("FUNAI_USER_ID=" + userId);
            cmd.add("-e");
            cmd.add("MONGO_HOST=127.0.0.1");
            cmd.add("-e");
            cmd.add("MONGO_PORT=" + props.getMongo().getPort());
            cmd.add("-e");
            cmd.add("MONGO_DB_PREFIX=" + props.getMongo().getDbNamePrefix());
        }

        // proxy env
        if (StringUtils.hasText(props.getHttpProxy())) {
            cmd.add("-e");
            cmd.add("HTTP_PROXY=" + props.getHttpProxy());
        }
        if (StringUtils.hasText(props.getHttpsProxy())) {
            cmd.add("-e");
            cmd.add("HTTPS_PROXY=" + props.getHttpsProxy());
        }
        if (StringUtils.hasText(props.getNoProxy())) {
            cmd.add("-e");
            cmd.add("NO_PROXY=" + props.getNoProxy());
        }

        cmd.add(meta.getImage());
        // keep-alive
        cmd.add("bash");
        cmd.add("-lc");
        cmd.add(buildContainerBootstrapCommand(userId));

        CommandResult r = commandRunner.run(Duration.ofSeconds(30), cmd);
        if (!r.isSuccess()) {
            throw new RuntimeException("创建 workspace 容器失败: userId=" + userId + ", out=" + r.getOutput());
        }
    }

    private String buildContainerBootstrapCommand(Long userId) {
        // 某些基础镜像/BusyBox 可能不支持 `sleep infinity`，会导致容器立刻退出，status 永远 EXITED
        // 用更通用的方式保持容器常驻；如启用 mongo，则尽量在容器启动时拉起 mongod（若镜像未包含 mongod，则跳过）
        if (props.getMongo() == null || !props.getMongo().isEnabled()) {
            return "while true; do sleep 3600; done";
        }

        String dbPath = props.getMongo().getContainerDbPath();
        String logDir = props.getMongo().getContainerLogDir();
        String logFile = logDir + "/" + (StringUtils.hasText(props.getMongo().getLogFileName()) ? props.getMongo().getLogFileName() : "mongod.log");
        String bindIp = StringUtils.hasText(props.getMongo().getBindIp()) ? props.getMongo().getBindIp() : "127.0.0.1";
        int port = props.getMongo().getPort() > 0 ? props.getMongo().getPort() : 27017;

        return ""
                + "set -e\n"
                + "echo \"[bootstrap] userId=" + userId + "\"\n"
                + "if command -v mongod >/dev/null 2>&1; then\n"
                + "  mkdir -p '" + dbPath + "' '" + logDir + "'\n"
                + "  echo \"[bootstrap] starting mongod...\" \n"
                + "  (mongod --dbpath '" + dbPath + "' --bind_ip '" + bindIp + "' --port " + port + " --logpath '" + logFile + "' --logappend >/dev/null 2>&1 &) || true\n"
                + "else\n"
                + "  echo \"[bootstrap] mongod not found in image, skip\" \n"
                + "fi\n"
                + "while true; do sleep 3600; done";
    }

    private Path resolveHostMongoUserDir(Long userId) {
        String root = props.getMongo() == null ? null : props.getMongo().getHostRoot();
        if (!StringUtils.hasText(root)) {
            throw new IllegalArgumentException("funai.workspace.mongo.hostRoot 未配置");
        }
        return Paths.get(root, String.valueOf(userId), "mongo");
    }

    private Path resolveHostMongoDbDir(Long userId) {
        return resolveHostMongoUserDir(userId).resolve("db");
    }

    private Path resolveHostMongoLogDir(Long userId) {
        return resolveHostMongoUserDir(userId).resolve("log");
    }

    private String queryContainerStatus(String containerName) {
        CommandResult exist = docker("inspect", "-f", "{{.State.Status}}", containerName);
        if (!exist.isSuccess()) {
            return "NOT_CREATED";
        }
        String s = normalizeDockerCliOutput(exist.getOutput());
        if (s.isEmpty()) return "UNKNOWN";
        if ("running".equalsIgnoreCase(s)) return "RUNNING";
        return s.toUpperCase();
    }

    /**
     * podman-docker 会把告警打印到 stdout：
     * "Emulate Docker CLI using podman. Create /etc/containers/nodocker to quiet msg."
     * 这里做一次清洗，只取最后一个非空行，避免把告警返回给前端。
     */
    private String normalizeDockerCliOutput(String output) {
        if (output == null) return "";
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    private CommandResult docker(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        for (String a : args) cmd.add(a);
        return commandRunner.run(CMD_TIMEOUT, cmd);
    }
}


