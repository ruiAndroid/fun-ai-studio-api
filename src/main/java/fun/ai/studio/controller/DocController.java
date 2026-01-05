package fun.ai.studio.controller;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 在线文档（将 src/main/resources/doc 下的 Markdown 渲染为 HTML）
 *
 * 访问示例：
 * - /doc/                        -> 文档首页
 * - /doc/README.md               -> 渲染 README
 * - /doc/部署-阿里云Linux3-单机版(Workspace+Nginx80-443).md
 * - /doc/raw/README.md           -> 原始 markdown
 */
@RestController
@RequestMapping("/doc")
public class DocController {

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder().build();

    @GetMapping({"", "/", "/index"})
    public ResponseEntity<String> index() {
        // 固定入口：README.md
        return renderMd("README.md");
    }

    @GetMapping(value = "/raw/{name:.+}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> raw(@PathVariable("name") String name) {
        String safe = sanitizeName(name);
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

    @GetMapping(value = "/{name:.+}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> render(@PathVariable("name") String name) {
        return renderMd(name);
    }

    private ResponseEntity<String> renderMd(String name) {
        String safe = sanitizeName(name);
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

    private String sanitizeName(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return null;
        // 禁止路径穿越
        if (s.contains("..") || s.contains("\\") || s.startsWith("/")) return null;
        return s;
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
        // 仅用于拼接路径（浏览器会自动 URL encode 非 ASCII），这里不做复杂 encode
        return s == null ? "" : s;
    }
}


