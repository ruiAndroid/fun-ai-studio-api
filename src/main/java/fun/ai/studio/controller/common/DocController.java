package fun.ai.studio.controller.common;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Hidden;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.UriUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * 在线文档（将 src/main/resources/doc 下的 Markdown 渲染为 HTML）
 *
 * 访问示例：
 * - /doc/                        -> 文档首页
 * - /doc/README.md               -> 渲染 README
 * - /doc/阿里云部署文档.md
 * - /doc/raw/README.md           -> 原始 markdown
 */
@RestController
@RequestMapping("/doc")
@Tag(name = "Fun AI 补充文档", description = "非http/https接口文档类型的补充")
public class DocController {

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder().build();

    @GetMapping({"", "/", "/index"})
    public ResponseEntity<String> index() {
        // 固定入口：README.md
        return renderMd("README.md");
    }

    /**
     * 原始 Markdown（支持子目录）：/doc/raw/**  -> classpath:/doc/**
     */
    @GetMapping(value = "/raw/**", produces = MediaType.TEXT_PLAIN_VALUE)
    @Hidden
    public ResponseEntity<String> raw(HttpServletRequest request) {
        String name = extractPathAfterPrefix(request, "/doc/raw/");
        String safe = sanitizePath(decodePath(name));
        if (safe == null) return ResponseEntity.notFound().build();
        ClassPathResource r = new ClassPathResource("doc/" + safe);
        if (!r.exists()) return ResponseEntity.notFound().build();
        try {
            String md = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
            return ResponseEntity.ok(md);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 渲染 Markdown（支持子目录）：/doc/** -> classpath:/doc/**
     */
    @GetMapping(value = "/**", produces = MediaType.TEXT_HTML_VALUE)
    @Hidden
    public ResponseEntity<String> render(HttpServletRequest request) {
        String name = extractPathAfterPrefix(request, "/doc/");
        return renderMd(decodePath(name));
    }

    private ResponseEntity<String> renderMd(String name) {
        String safe = sanitizePath(name);
        if (safe == null) return ResponseEntity.notFound().build();
        if (!safe.toLowerCase().endsWith(".md")) {
            safe = safe + ".md";
        }
        ClassPathResource r = new ClassPathResource("doc/" + safe);
        if (!r.exists()) return ResponseEntity.notFound().build();
        try {
            String md = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
            Node doc = MARKDOWN_PARSER.parse(md);
            String body = HTML_RENDERER.render(doc);
            String html = wrapHtml(safe, body);
            return ResponseEntity.ok(html);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private String sanitizePath(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return null;
        // 禁止路径穿越
        if (s.contains("..") || s.contains("\\") || s.startsWith("/")) return null;
        return s;
    }


    private String extractPathAfterPrefix(HttpServletRequest request, String prefix) {
        if (request == null || prefix == null) return null;
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri != null && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        if (uri == null) return null;
        if (!uri.startsWith(prefix)) return null;
        return uri.substring(prefix.length());
    }

    /**
     * 兼容用户直接访问 URL-encoded 路径（例如 /doc/%E9%98%BF...md）。
     * - 若 name 已被容器解码（/doc/阿里云部署文档.md），decode 不会改变结果
     * - 若 name 仍是 %XX 编码，这里按 UTF-8 解码为实际文件名
     */
    private String decodePath(String name) {
        if (name == null) return null;
        try {
            // UriUtils.decode 对 path 语义更合适（不会把 '+' 当空格来处理 query 的语义）
            return UriUtils.decode(name, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            return name;
        }
    }

    private String wrapHtml(String title, String bodyHtml) {
        // 极简样式：支持 code/pre/table
        return "<!doctype html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
                + "<title>" + escape(title) + "</title>"
                + "<style>"
                + "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial;margin:24px;max-width:980px}"
                + "a{color:#0969da;text-decoration:none} a:hover{text-decoration:underline}"
                + "pre{background:#f6f8fa;padding:12px;border-radius:10px;overflow:auto}"
                + "code{background:#f6f8fa;padding:2px 6px;border-radius:6px}"
                + "table{border-collapse:collapse} td,th{border:1px solid #d0d7de;padding:6px 10px}"
                + "blockquote{border-left:4px solid #d0d7de;padding-left:12px;color:#57606a}"
                + "</style></head><body>"
                + "<div style=\"margin-bottom:16px\">"
                + "<a href=\"/doc/\">文档首页</a> · "
                + "<a href=\"/doc/raw/" + urlPath(title) + "\">查看原始 Markdown</a>"
                + "</div>"
                + bodyHtml
                + "</body></html>";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String urlPath(String s) {
        // 用于拼接 /doc/raw/** 链接：对中文/空格等做 URL encode，但保留路径分隔符 '/'
        return s == null ? "" : UriUtils.encodePath(s, StandardCharsets.UTF_8);
    }
}


