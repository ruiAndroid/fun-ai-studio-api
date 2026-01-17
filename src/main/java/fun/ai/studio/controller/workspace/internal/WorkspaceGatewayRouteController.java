package fun.ai.studio.controller.workspace.internal;

import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.workspace.WorkspaceNodeResolver;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入口 Nginx / 网关使用：根据 userId 获取该用户落点节点的 Nginx 上游地址。
 *
 * 设计要点：
 * - 返回 204 + Header，避免 body
 * - 落点为粘性：首次访问会分配节点
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/internal/gateway")
@Hidden
public class WorkspaceGatewayRouteController {

    private final WorkspaceNodeResolver nodeResolver;

    public WorkspaceGatewayRouteController(WorkspaceNodeResolver nodeResolver) {
        this.nodeResolver = nodeResolver;
    }

    @GetMapping("/node")
    @Operation(summary = "（内部）网关查询节点上游", description = "供入口 Nginx auth_request 使用：根据 userId 返回 X-WS-Node header（nginx_base_url）。")
    public ResponseEntity<Void> nodeForUser(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        FunAiWorkspaceNode node = nodeResolver.resolve(userId);
        return ResponseEntity.noContent()
                .header("X-WS-Node", node.getNginxBaseUrl())
                .header("X-WS-NodeId", String.valueOf(node.getId()))
                .build();
    }
}


