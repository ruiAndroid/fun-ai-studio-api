package fun.ai.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunMeta;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import fun.ai.studio.service.FunAiAppService;
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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class FunAiWorkspaceServiceImpl implements FunAiWorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceServiceImpl.class);

    private final WorkspaceProperties props;
    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final FunAiAppService funAiAppService;

    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(10);

    public FunAiWorkspaceServiceImpl(WorkspaceProperties props,
                                     CommandRunner commandRunner,
                                     ObjectMapper objectMapper,
                                     FunAiAppService funAiAppService) {
        this.props = props;
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
        this.funAiAppService = funAiAppService;
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
        resp.setContainerStatus(queryContainerStatus(containerName));
        return resp;
    }

    @Override
    public FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId) {
        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        assertAppOwned(userId, appId);
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

        FunAiWorkspaceInfoResponse ws = ensureWorkspace(userId);
        // 确保应用目录存在
        ensureAppDir(userId, appId);

        String containerName = ws.getContainerName();
        String containerAppDir = Paths.get(props.getContainerWorkdir(), "apps", String.valueOf(appId)).toString().replace("\\", "/");
        String runDir = Paths.get(props.getContainerWorkdir(), "run").toString().replace("\\", "/");
        String pidFile = runDir + "/dev.pid";
        String metaFile = runDir + "/current.json";
        String logFile = runDir + "/dev.log";

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
                + "mkdir -p \"$RUN_DIR\"\n"
                + "if [ -f \"$PID_FILE\" ]; then\n"
                + "  pid=$(cat \"$PID_FILE\" 2>/dev/null || true)\n"
                + "  if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then\n"
                + "    echo \"ALREADY_RUNNING:$pid\"\n"
                + "    exit 42\n"
                + "  fi\n"
                + "fi\n"
                + "rm -f \"$PID_FILE\" || true\n"
                + "printf '{\"appId\":%s,\"type\":\"DEV\",\"pid\":null,\"startedAt\":%s,\"logPath\":\"%s\"}' "
                + appId + " \"$(date +%s)\" \"" + logFile + "\" > \"$META_FILE\"\n"
                + "APP_DIR='" + containerAppDir + "'\n"
                + "PORT='" + ws.getContainerPort() + "'\n"
                + "nohup bash -lc \"\n"
                + "  set -e\n"
                + "  cd \\\"$APP_DIR\\\"\\n"
                + "  if [ ! -f package.json ]; then echo 'package.json not found' >>\\\"$LOG_FILE\\\"; exit 2; fi\\n"
                + "  npm config set registry https://registry.npmmirror.com >/dev/null 2>&1 || true\\n"
                + "  if [ ! -d node_modules ]; then npm install >>\\\"$LOG_FILE\\\" 2>&1; fi\\n"
                + "  setsid sh -c \\\"npm run dev -- --host 0.0.0.0 --port $PORT\\\" >>\\\"$LOG_FILE\\\" 2>&1 < /dev/null &\\n"
                + "  pid=\\$!\\n"
                + "  echo \\\"\\$pid\\\" > \\\"$PID_FILE\\\"\\n"
                + "  printf '{\\\\\\\"appId\\\\\\\":%s,\\\\\\\"type\\\\\\\":\\\\\\\"DEV\\\\\\\",\\\\\\\"pid\\\\\\\":%s,\\\\\\\"startedAt\\\\\\\":%s,\\\\\\\"logPath\\\\\\\":\\\\\\\"%s\\\\\\\"}' "
                + appId + " \\\"\\$pid\\\" \\\"\\$(date +%s)\\\" \\\"" + logFile + "\\\" > \\\"$META_FILE\\\"\\n"
                + "\" >/dev/null 2>&1 &\n"
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
        return getRunStatus(userId);
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
            return resp;
        }

        try {
            FunAiWorkspaceRunMeta meta = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8), FunAiWorkspaceRunMeta.class);
            resp.setAppId(meta.getAppId());
            resp.setPid(meta.getPid());
            resp.setLogPath(meta.getLogPath());

            // pid 为空：说明 start 已触发，但后台脚本尚未写入 pid（npm install 进行中）
            if (meta.getPid() == null) {
                resp.setState("STARTING");
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
            return resp;
        } catch (Exception e) {
            log.warn("read run meta failed: userId={}, error={}", userId, e.getMessage());
            resp.setState("UNKNOWN");
            resp.setMessage("读取运行状态失败: " + e.getMessage());
            return resp;
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
        return base + ":" + ws.getHostPort() + "/";
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
        // 某些基础镜像/BusyBox 可能不支持 `sleep infinity`，会导致容器立刻退出，status 永远 EXITED
        // 用更通用的方式保持容器常驻
        cmd.add("while true; do sleep 3600; done");

        CommandResult r = commandRunner.run(Duration.ofSeconds(30), cmd);
        if (!r.isSuccess()) {
            throw new RuntimeException("创建 workspace 容器失败: userId=" + userId + ", out=" + r.getOutput());
        }
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


