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
@Tag(name = "Admin Deploy Nodes")
public class AdminDeployNodeController {

    @GetMapping("/list")
    @Operation(summary = "占位：deploy-node 列表（暂未实现）")
    public Result<List<Object>> list() {
        return Result.success(List.of());
    }
}


