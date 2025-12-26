package fun.ai.studio.controller;

import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.enums.FunAiAppStatus;
import fun.ai.studio.service.FunAiAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * FunAI 应用站点访问（方式B：由 Spring Boot 提供静态资源）
 *
 * 访问规则：
 * - /fun-ai-app/{userId}/{appId}/            -> dist/index.html
 * - /fun-ai-app/{userId}/{appId}/assets/...  -> dist 下对应文件
 * - SPA 路由：如果资源不存在，则回退到 index.html
 */
@Controller
public class FunAiAppSiteController {

    private static final Logger log = LoggerFactory.getLogger(FunAiAppSiteController.class);

    private final FunAiAppService funAiAppService;

    // 与 FunAiAppServiceImpl 保持一致：用户应用根目录
    private final String userPath;

    /**
     * 站点资源请求会非常频繁（index.html + 多个 assets）。
     * 如果每个请求都查库，会把一次页面加载放大成几十次 DB 查询。
     * 这里做一个轻量本地 TTL 缓存（单机内存；多实例场景每实例各自缓存）。
     */
    private static final long APP_META_CACHE_TTL_MS = 10_000L;
    private static final ConcurrentMap<String, CacheEntry> APP_META_CACHE = new ConcurrentHashMap<>();

    private static final class CacheEntry {
        final Path distDir;
        final long expireAtMs;

        private CacheEntry(Path distDir, long expireAtMs) {
            this.distDir = distDir;
            this.expireAtMs = expireAtMs;
        }

        boolean isExpired(long now) {
            return now >= expireAtMs;
        }
    }

    public FunAiAppSiteController(FunAiAppService funAiAppService,
                                  @org.springframework.beans.factory.annotation.Value("${funai.userPath}") String userPath) {
        this.funAiAppService = funAiAppService;
        this.userPath = userPath;
    }

    @GetMapping({"/fun-ai-app/{userId}/{appId}", "/fun-ai-app/{userId}/{appId}/", "/fun-ai-app/{userId}/{appId}/**"})
    public ResponseEntity<Resource> serve(
            @PathVariable Long userId,
            @PathVariable Long appId,
            HttpServletRequest request
    ) {
        try {
            // 0) 规范化URL：不带尾随 / 的入口路径统一 302 到带 /
            // 例如 /fun-ai-app/1/100 -> /fun-ai-app/1/100/
            String uri = request.getRequestURI();
            String prefix = "/fun-ai-app/" + userId + "/" + appId;
            if (prefix.equals(uri)) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, prefix + "/")
                        .build();
            }

            String basePath = sanitizeUserPath(userPath);
            if (basePath == null || basePath.isEmpty()) {
                return ResponseEntity.internalServerError().build();
            }

            // 1) 获取并缓存 dist 目录（避免每个静态资源都查库+探测目录）
            long now = System.currentTimeMillis();
            String cacheKey = userId + ":" + appId;
            CacheEntry cached = APP_META_CACHE.get(cacheKey);
            if (cached == null || cached.isExpired(now)) {
                // 1.1) 校验应用归属（DB）
                FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
                if (app == null || app.getAppStatus() == null || app.getAppStatus() != FunAiAppStatus.READY.code()) {
                    // 负缓存：短时间内重复请求直接返回，避免被打爆
                    APP_META_CACHE.put(cacheKey, new CacheEntry(null, now + APP_META_CACHE_TTL_MS));
                    return ResponseEntity.notFound().build();
                }

                // 1.2) 计算 dist 目录：{userPath}/{userId}/{sanitize(appName)}/deploy/{root}/dist
                Path appDir = Paths.get(basePath, String.valueOf(userId), sanitizeFileName(app.getAppName()));
                Path deployDir = appDir.resolve("deploy");
                Path projectRoot = detectProjectRoot(deployDir);
                Path distDir = projectRoot.resolve("dist");
                cached = new CacheEntry(distDir, now + APP_META_CACHE_TTL_MS);
                APP_META_CACHE.put(cacheKey, cached);
            }

            Path distDir = cached.distDir;
            if (distDir == null) {
                return ResponseEntity.notFound().build();
            }

            if (Files.notExists(distDir)) {
                // build 未完成 或 build 失败
                return ResponseEntity.notFound().build();
            }

            // 3) 解析请求路径中 dist 下的相对路径
            uri = request.getRequestURI(); // e.g. /fun-ai-app/1/100/assets/index-xxx.js
            String rel = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "/";
            if (rel.isEmpty() || "/".equals(rel)) {
                rel = "/index.html";
            }
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }

            Path target = distDir.resolve(rel).normalize();
            if (!target.startsWith(distDir)) {
                return ResponseEntity.badRequest().build();
            }

            // 4) SPA 路由回退：文件不存在则返回 index.html
            if (Files.notExists(target) || Files.isDirectory(target)) {
                target = distDir.resolve("index.html");
            }

            if (Files.notExists(target)) {
                return ResponseEntity.notFound().build();
            }

            FileSystemResource resource = new FileSystemResource(target.toFile());
            MediaType mediaType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);

            // 禁止缓存（你如果想缓存可改为 max-age）
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .contentType(mediaType)
                    .body(resource);
        } catch (Exception e) {
            log.error("serve fun-ai-app failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String sanitizeUserPath(String p) {
        if (p == null) return null;
        return p.trim().replaceAll("^[\"']|[\"']$", "");
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unnamed";
        return fileName.replaceAll("[<>:\"/\\\\|?*]", "_");
    }

    private Path detectProjectRoot(Path deployDir) {
        try {
            if (Files.exists(deployDir.resolve("package.json"))) {
                return deployDir;
            }
            var children = java.util.List.<Path>of();
            try (Stream<Path> stream = Files.list(deployDir)) {
                children = stream.filter(Files::isDirectory).toList();
            }
            if (children.size() == 1) {
                Path only = children.get(0);
                if (Files.exists(only.resolve("package.json"))) {
                    return only;
                }
            }
        } catch (Exception ignore) {
        }
        return deployDir;
    }
}


