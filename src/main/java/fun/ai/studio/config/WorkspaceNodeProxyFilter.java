package fun.ai.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API 服务器（小机）：应用层反向代理（/api/fun-ai/workspace/** -> Workspace 开发服务器（大机）workspace-node）。
 *
 * - 对外 URL/参数不变
 * - API 服务器（小机）仍负责鉴权/授权（Spring Security）
 * - 转发时注入 X-WS-* 头以通过 Workspace 开发服务器（大机）的 InternalAuthFilter（HMAC-SHA256）
 *
 * 说明：WebSocket 握手不走此 filter（由 API 服务器（小机）Nginx 反代到 Workspace 开发服务器（大机），且 Workspace 开发服务器（大机）对该路径跳过签名）。
 */
public class WorkspaceNodeProxyFilter extends OncePerRequestFilter {
    private static final String WORKSPACE_API_PREFIX = "/api/fun-ai/workspace/";

    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";

    // hop-by-hop headers：RFC 7230
    private static final Set<String> HOP_BY_HOP = new HashSet<>(List.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    ));

    private final WorkspaceNodeProxyProperties props;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(WorkspaceNodeProxyFilter.class);

    public WorkspaceNodeProxyFilter(WorkspaceNodeProxyProperties props) {
        this.props = props;
        HttpClient.Builder b = HttpClient.newBuilder();
        if (props != null && props.getConnectTimeoutMs() > 0) {
            b.connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        }
        this.httpClient = b.build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) return true;
        if (props == null || !props.isEnabled()) return true;
        String uri = request.getRequestURI();
        if (uri == null) return true;
        if (!uri.startsWith(WORKSPACE_API_PREFIX)) return true;
        // Mongo Explorer：需要在 API 服务器（小机）侧做 appOwned 等业务校验，再由 controller 调用 WorkspaceNodeClient 转发到 workspace-node
        if (uri.startsWith("/api/fun-ai/workspace/mongo/")) return true;
        // WebSocket 握手由 Nginx 直转发到 Workspace 开发服务器（大机）（HTTP client 无法代理 Upgrade）
        return uri.startsWith("/api/fun-ai/workspace/ws/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 兜底：如果未启用，则走原链路
        if (props == null || !props.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String baseUrl = props.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            deny(response, 500, "workspace-node-proxy.base-url is empty");
            return;
        }
        String secret = props.getSharedSecret();
        if (secret == null || secret.isBlank() || "CHANGE_ME_STRONG_SECRET".equals(secret)) {
            deny(response, 500, "workspace-node-proxy.shared-secret is empty/default");
            return;
        }

        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        String query = request.getQueryString() == null ? "" : request.getQueryString();

        // 1) 读取 body：为签名计算 sha256；大体积请求落盘临时文件避免 OOM
        BodyHolder body = null;
        try {
            body = readBodyMaybeSpoolToDisk(request, props.getBodySpoolThresholdBytes());
            String bodySha = body.sha256Hex;

            long ts = Instant.now().getEpochSecond();
            String nonce = randomNonce();
            String canonical = WorkspaceNodeProxySigner.canonical(method, path, query, bodySha, ts, nonce);
            String sig;
            try {
                sig = WorkspaceNodeProxySigner.hmacSha256Base64(secret, canonical);
            } catch (Exception e) {
                deny(response, 500, "sign failed: " + e.getMessage());
                return;
            }

            // 2) 发起上游请求
            URI uri = URI.create(joinUrl(baseUrl, path, query));
            HttpRequest.Builder reqB = HttpRequest.newBuilder().uri(uri);
            if (props.getReadTimeoutMs() > 0) {
                reqB.timeout(Duration.ofMillis(props.getReadTimeoutMs()));
            }
            reqB.method(method, body.publisher());

            // 2.1 复制请求头（过滤 hop-by-hop）
            copyRequestHeaders(request, reqB);
            // 2.2 注入签名头（覆盖用户可能伪造的同名 header）
            reqB.setHeader(HDR_TS, String.valueOf(ts));
            reqB.setHeader(HDR_NONCE, nonce);
            reqB.setHeader(HDR_SIG, sig);

            HttpResponse<InputStream> upstream;
            try {
                upstream = httpClient.send(reqB.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                deny(response, 502, "upstream interrupted");
                return;
            } catch (Exception e) {
                deny(response, 502, "upstream error: " + e.getMessage());
                return;
            }

            // 3) 回写响应
            // 诊断：标记该响应来自应用层代理，避免“到底是 API 服务器还是 workspace-dev 抛错”无法判断
            response.setHeader("X-WS-Proxied", "1");
            response.setHeader("X-WS-Upstream", baseUrl);
            response.setStatus(upstream.statusCode());
            copyResponseHeaders(upstream, response);

            try (InputStream in = upstream.body()) {
                if (in != null) {
                    // SSE/下载等场景需要流式输出
                    try (OutputStream out = response.getOutputStream()) {
                        StreamUtils.copy(in, out);
                        out.flush();
                    }
                }
            }
        } finally {
            if (body != null) {
                body.closeQuietly();
            }
        }
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder reqB) {
        String contentTypeSeen = null;
        for (Enumeration<String> en = request.getHeaderNames(); en != null && en.hasMoreElements(); ) {
            String name = en.nextElement();
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower)) continue;
            if ("host".equals(lower)) continue;
            // JDK HttpClient 禁止手动设置部分受限 header（会抛 IllegalArgumentException）
            // 典型场景：multipart 上传会带 Content-Length；该值应由 HttpClient 根据 BodyPublisher 自动计算/设置。
            if ("content-length".equals(lower)) continue;
            if ("expect".equals(lower)) continue;
            if (HDR_SIG.equalsIgnoreCase(name) || HDR_TS.equalsIgnoreCase(name) || HDR_NONCE.equalsIgnoreCase(name)) continue;

            for (Enumeration<String> vals = request.getHeaders(name); vals != null && vals.hasMoreElements(); ) {
                String v = vals.nextElement();
                if (v == null) continue;
                // Content-Type 对 multipart 解析至关重要（必须带 boundary）。有些容器/代理场景下 headerNames 可能拿不到或出现重复值，
                // 这里先记录，最后统一用 setHeader() 兜底覆盖，确保上游收到的 Content-Type 正确。
                if ("content-type".equals(lower) && contentTypeSeen == null) {
                    contentTypeSeen = v;
                    continue;
                }
                // HttpClient 不允许重复 setHeader 覆盖时叠加；这里用 header() 追加
                reqB.header(name, v);
            }
        }

        // 强制兜底 Content-Type：优先使用 servlet 解析后的 getContentType()（更可靠），否则退回到 header 里看到的值
        String ct = request.getContentType();
        if (ct == null || ct.isBlank()) ct = contentTypeSeen;
        if (ct != null && !ct.isBlank()) {
            reqB.setHeader("Content-Type", ct);
        }

        // 仅对 multipart 上传接口输出一条诊断日志，方便快速定位 “file part 丢失” 的真实原因
        String uri = request.getRequestURI();
        if (uri != null && (uri.contains("/files/upload-zip") || uri.contains("/files/upload-file"))) {
            long len = request.getContentLengthLong();
            log.info("workspace-node-proxy multipart diag: uri={}, method={}, contentType={}, contentLength={}", uri, request.getMethod(), ct, len);
        }
    }

    private void copyResponseHeaders(HttpResponse<?> upstream, HttpServletResponse resp) {
        upstream.headers().map().forEach((k, values) -> {
            if (k == null) return;
            String lower = k.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower)) return;
            if ("content-length".equals(lower)) return; // 由 servlet 容器决定（可能 chunked）
            if (values == null) return;
            for (String v : values) {
                if (v == null) continue;
                resp.addHeader(k, v);
            }
        });
    }

    private String joinUrl(String baseUrl, String path, String query) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = (path == null || path.isBlank()) ? "" : path;
        String q = (query == null || query.isBlank()) ? "" : ("?" + query);
        return b + p + q;
    }

    private String randomNonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code);
        resp.setContentType(MediaType.TEXT_PLAIN_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(msg == null ? "" : msg);
    }

    private static final class BodyHolder {
        final byte[] bytes;
        final Path tempFile;
        final String sha256Hex;

        private BodyHolder(byte[] bytes, Path tempFile, String sha256Hex) {
            this.bytes = bytes;
            this.tempFile = tempFile;
            this.sha256Hex = sha256Hex;
        }

        HttpRequest.BodyPublisher publisher() throws IOException {
            if (tempFile != null) {
                // 重要：multipart 上传如果使用 ofInputStream()，HttpClient 可能采用 chunked 传输，部分服务端对 chunked+multipart 解析不兼容，
                // 会导致 @RequestPart("file") 绑定不到（Required part 'file' is not present）。
                // ofFile() 能提供确定长度，更接近原始客户端请求语义，也避免一次性读入内存。
                return HttpRequest.BodyPublishers.ofFile(tempFile);
            }
            if (bytes == null || bytes.length == 0) {
                return HttpRequest.BodyPublishers.noBody();
            }
            return HttpRequest.BodyPublishers.ofByteArray(bytes);
        }

        void closeQuietly() {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private BodyHolder readBodyMaybeSpoolToDisk(HttpServletRequest request, long thresholdBytes) throws IOException {
        long len = request.getContentLengthLong();
        // GET/HEAD 等一般没有 body
        if (len == 0 && ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod()))) {
            try {
                String sha = WorkspaceNodeProxySigner.sha256Hex(new byte[0]);
                return new BodyHolder(new byte[0], null, sha);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        // content-length 未知或很大：落盘
        boolean spool = (len < 0) || (len > Math.max(0, thresholdBytes));
        if (!spool) {
            byte[] bytes = StreamUtils.copyToByteArray(request.getInputStream());
            try {
                String sha = WorkspaceNodeProxySigner.sha256Hex(bytes);
                return new BodyHolder(bytes, null, sha);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        Path tmp = Files.createTempFile("ws-proxy-body-", ".bin");
        try (InputStream in = request.getInputStream(); OutputStream out = new FileOutputStream(tmp.toFile())) {
            byte[] buf = new byte[8192];
            int n;
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException(e);
            }
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            out.flush();
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return new BodyHolder(null, tmp, sb.toString());
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignore) {
            }
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }
}


