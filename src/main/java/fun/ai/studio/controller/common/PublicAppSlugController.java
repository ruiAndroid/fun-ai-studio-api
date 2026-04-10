package fun.ai.studio.controller.common;

import fun.ai.studio.app.AppSlugPolicy;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.enums.FunAiAppStatus;
import fun.ai.studio.service.FunAiAppService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公网别名入口：/{appSlug} -> 302 /runtime/{appId}
 */
@RestController
@Hidden
public class PublicAppSlugController {

    private final FunAiAppService funAiAppService;

    public PublicAppSlugController(FunAiAppService funAiAppService) {
        this.funAiAppService = funAiAppService;
    }

    @GetMapping(value = "/{appSlug:[a-z0-9]+(?:-[a-z0-9]+)*}")
    public ResponseEntity<String> route(@PathVariable("appSlug") String appSlug) {
        String normalized = AppSlugPolicy.normalize(appSlug);
        if (!AppSlugPolicy.isValidFormat(normalized) || AppSlugPolicy.isReserved(normalized)) {
            return ResponseEntity.notFound().build();
        }

        FunAiApp app = funAiAppService.getAppBySlug(normalized);
        if (app == null) {
            return ResponseEntity.notFound().build();
        }

        if (app.getAppStatus() == null || app.getAppStatus() != FunAiAppStatus.READY.code()) {
            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.TEXT_HTML)
                    .body(buildPendingHtml(normalized));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, "/runtime/" + app.getId());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String buildPendingHtml(String slug) {
        String safe = slug == null ? "" : slug.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!doctype html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
                + "<title>建设中</title>"
                + "<style>"
                + "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial;margin:40px;background:#f8fafc;color:#0f172a}"
                + ".card{max-width:680px;margin:0 auto;background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:32px}"
                + "h1{margin:0 0 12px;font-size:28px}"
                + "p{line-height:1.7;color:#475569}"
                + "code{background:#f1f5f9;padding:2px 6px;border-radius:6px}"
                + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h1>应用建设中</h1>"
                + "<p>访问名 <code>" + safe + "</code> 已存在，但对应应用暂未发布成功，请稍后再试。</p>"
                + "</div></body></html>";
    }
}
