package fun.ai.studio.controller.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 根路径兼容：/{name}.md -> 302 /doc/{name}.md
 *
 * 背景：/doc/** 在安全白名单中，但根路径 /** 默认需要登录。
 * 通过仅放行根路径 /*.md，并做 302 跳转，可以兼容历史/手工输入链接，又不扩大匿名访问面。
 */
@RestController
public class DocRedirectController {

    @GetMapping(value = "/{file:.+\\.md}")
    public ResponseEntity<Void> redirectMd(@PathVariable("file") String file) {
        // 安全：仅允许单段文件名（根路径），禁止路径穿越
        if (file == null) return ResponseEntity.notFound().build();
        String s = file.trim();
        if (s.isEmpty()) return ResponseEntity.notFound().build();
        if (s.contains("..") || s.contains("/") || s.contains("\\")) return ResponseEntity.notFound().build();

        // 跳转到 /doc/ 下的同名资源；对中文等做 encode，避免 Location header 非 ASCII 问题
        String encoded = UriUtils.encodePath(s, StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, "/doc/" + encoded);
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}


