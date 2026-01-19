package fun.ai.studio.controller.admin;

import fun.ai.studio.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预留：deploy-node 管理（后续部署集群用）
 * 当前仅提供占位接口，便于前端/运维入口稳定。
 */
@RestController
@RequestMapping("/api/fun-ai/admin/deploy-nodes")
@Tag(
        name = "Admin Deploy Nodes",
        description = "预留：Deploy 节点管理（后续部署集群）。\n\n"
                + "访问入口：\n"
                + "- http://{{公网ip}}/nodes.html#token={{adminToken}}\n"
                + "- http://{{公网ip}}/nodes-admin.html?mode=deploy#token={{adminToken}}\n\n"
                + "当前状态：暂未开放（仅占位接口）。\n\n"
                + "鉴权方式：\n"
                + "- Header：X-Admin-Token={{adminToken}}\n"
                + "- 来源 IP：需在 funai.admin.allowed-ips 白名单内"
)
public class AdminDeployNodeController {

    @GetMapping("/list")
    @Operation(summary = "占位：deploy-node 列表（暂未实现）")
    public Result<List<Object>> list() {
        return Result.success(List.of());
    }
}


