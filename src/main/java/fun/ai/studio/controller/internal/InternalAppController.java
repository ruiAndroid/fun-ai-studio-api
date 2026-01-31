package fun.ai.studio.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.mapper.FunAiAppMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内部 API：用于服务间调用（如 workspace 和 runtime-agent 定时清理任务）
 * 
 * 注意：这些 API 不需要用户认证，但应该通过内网访问
 */
@RestController
@RequestMapping("/api/fun-ai/internal/apps")
@Hidden
public class InternalAppController {

    private final FunAiAppMapper appMapper;

    public InternalAppController(FunAiAppMapper appMapper) {
        this.appMapper = appMapper;
    }

    /**
     * 获取所有应用 ID（用于定时清理任务）
     * 
     * @return 所有应用 ID 列表
     */
    @GetMapping("/ids")
    public Result<Map<String, Object>> getAllAppIds() {
        try {
            List<FunAiApp> apps = appMapper.selectList(new QueryWrapper<>());
            List<Long> appIds = apps.stream()
                    .map(FunAiApp::getId)
                    .filter(id -> id != null)
                    .collect(Collectors.toList());
            
            Map<String, Object> data = new HashMap<>();
            data.put("appIds", appIds);
            data.put("count", appIds.size());
            
            return Result.success(data);
        } catch (Exception e) {
            return Result.error("获取应用 ID 列表失败: " + e.getMessage());
        }
    }
}
