package fun.ai.studio.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 旧的无容器部署/静态站点访问入口：已永久关闭（全量 workspace 在线运行）。
 */
@Controller
public class FunAiAppSiteController {

    @GetMapping({"/fun-ai-app/{userId}/{appId}", "/fun-ai-app/{userId}/{appId}/", "/fun-ai-app/{userId}/{appId}/**"})
    public ResponseEntity<Resource> serve(
            @PathVariable Long userId,
            @PathVariable Long appId,
            HttpServletRequest request
    ) {
        return ResponseEntity.notFound().build();
    }
}


