package fun.ai.studio.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunMeta;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
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
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class FunAiWorkspaceServiceImpl implements FunAiWorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceServiceImpl.class);

    private final WorkspaceProperties props;
    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;

    private static final Duration CMD_TIMEOUT = Duration.ofSeconds(10);

    public FunAiWorkspaceServiceImpl(WorkspaceProperties props, CommandRunner commandRunner, ObjectMapper objectMapper) {
        this.props = props;
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
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
                + "cd '" + containerAppDir + "'\n"
                + "if [ ! -f package.json ]; then echo \"package.json not found\"; exit 2; fi\n"
                + "npm config set registry https://registry.npmmirror.com >/dev/null 2>&1 || true\n"
                + "if [ ! -d node_modules ]; then npm install; fi\n"
                + "setsid sh -c \"npm run dev -- --host 0.0.0.0 --port " + ws.getContainerPort() + "\" >\"$LOG_FILE\" 2>&1 < /dev/null &\n"
                + "pid=$!\n"
                + "echo \"$pid\" > \"$PID_FILE\"\n"
                + "echo '{\"appId\":" + appId + ",\"type\":\"DEV\",\"pid\":'\"$pid\"',\"startedAt\":'\"$(date +%s)\"',\"logPath\":\"" + logFile + "\"}' > \"$META_FILE\"\n"
                + "echo \"STARTED:$pid\"\n";

        CommandResult r = docker("exec", containerName, "bash", "-lc", script);
        if (r.getExitCode() == 42) {
            FunAiWorkspaceRunStatusResponse status = getRunStatus(userId);
            status.setMessage("已在运行中，当前仅允许同时运行一个应用");
            return status;
        }
        if (!r.isSuccess()) {
            throw new RuntimeException("启动 dev 失败: " + r.getOutput());
        }
        return getRunStatus(userId);
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

            if (meta.getPid() == null) {
                resp.setState("DEAD");
                return resp;
            }

            // 进程存活校验（容器内）
            CommandResult alive = docker("exec", ws.getContainerName(), "bash", "-lc",
                    "kill -0 " + meta.getPid() + " 2>/dev/null && echo RUNNING || echo DEAD");
            String s = alive.getOutput() == null ? "" : alive.getOutput().trim();
            if (s.contains("RUNNING")) {
                resp.setState("RUNNING");
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

    private void assertEnabled() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("workspace 功能未启用（funai.workspace.enabled=false）");
        }
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
        cmd.add("sleep infinity");

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
        String s = exist.getOutput() == null ? "" : exist.getOutput().trim();
        if (s.isEmpty()) return "UNKNOWN";
        if ("running".equalsIgnoreCase(s)) return "RUNNING";
        return s.toUpperCase();
    }

    private CommandResult docker(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        for (String a : args) cmd.add(a);
        return commandRunner.run(CMD_TIMEOUT, cmd);
    }
}


