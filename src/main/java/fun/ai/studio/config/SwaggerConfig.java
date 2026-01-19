package fun.ai.studio.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Map;

@Configuration
public class SwaggerConfig implements WebMvcConfigurer {

    private static final String WORKSPACE_API_PREFIX = "/api/fun-ai/workspace/";

    /**
     * 双机部署提示：Workspace 接口对外仍由 API 服务器（小机）展示/鉴权，但会转发到 Workspace 开发服务器（大机）的 workspace-node 执行。
     * <p>
     * 注意：这里的文案刻意写成“在双机部署时”，以兼容单机开发环境。
     */
    private static final String WORKSPACE_FORWARDED_HINT =
            "【双机部署提示】该接口在 API 服务器（小机）上展示/鉴权；在双机部署时会转发到 Workspace 开发服务器（大机）的容器节点（workspace-node）执行。\n\n"
            + "【常见错误提示】如果你看到 code=502，通常表示“代理链路未生效或 Workspace 开发服务器（大机）不可用”。请依次检查：\n"
            + "1) API 服务器（小机）配置：workspace-node-proxy.enabled=true\n"
            + "2) API 服务器（小机）到 Workspace 开发服务器（大机）7001 的网络/安全组是否放行\n"
            + "3) shared-secret 是否一致（API 服务器（小机）workspace-node-proxy.shared-secret 与 Workspace 开发服务器（大机）workspace-node.internal.shared-secret）\n"
            + "4) Workspace 开发服务器（大机）workspace-node 服务是否在线（7001）";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FunAiStudioApi")
                        .description("""
                                风行Ai创作平台-Api接口文档

                                【双机部署总览】
                                - API 服务器（小机）：对外入口（Nginx）、业务 API（fun-ai-studio-api）、MySQL；负责鉴权/授权与业务数据。
                                - Workspace 开发服务器（大机）：容器节点（workspace-node）、workspace 用户容器、verdaccio/npm 缓存、workspace 落盘目录等重负载能力。

                                【接口/流量说明】
                                - `/api/fun-ai/workspace/**`：对外仍由 API 服务器（小机）暴露（本 Swagger 可见），双机部署时会转发到 Workspace 开发服务器（大机）容器节点执行。
                                - `/ws/{userId}/...`：用户预览入口，双机部署时由 API 服务器（小机）转发到 Workspace 开发服务器（大机）Nginx，再反代到该用户容器的 hostPort。

                                【节点管理（运维）】
                                - 入口页：`/admin/nodes.html#token={{adminToken}}`
                                - Workspace 节点：`/admin/nodes-admin.html?mode=workspace#token={{adminToken}}`
                                - Deploy 节点：`/admin/nodes-admin.html?mode=deploy#token={{adminToken}}`（暂未开放）
                                - 鉴权：Header `X-Admin-Token` + 来源 IP 白名单（见 `funai.admin.*` 配置）

                                【排障提示】
                                - 容器/端口池/运行日志/verdaccio/npm 安装问题：优先在 Workspace 开发服务器（大机）侧排查。
                                """)
                        .version("1.0")
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")))
                .addSecurityItem(new SecurityRequirement().addList("Authorization"))
                .components(new Components()
                        .addSecuritySchemes("Authorization",
                                new SecurityScheme()
                                        .name("Authorization")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
    
    /**
     * 为 Swagger 中的 workspace 接口自动追加“双机部署转发”提示，避免访问不到 Workspace 开发服务器（大机）Swagger 时信息缺失。
     */
    @Bean
    public OpenApiCustomizer workspaceForwardedHintCustomiser() {
        return openApi -> {
            // 1) 给 workspace 相关 paths/operations 增加说明
            if (openApi.getPaths() != null) {
                for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> e : openApi.getPaths().entrySet()) {
                    String path = e.getKey();
                    if (path == null || !path.startsWith(WORKSPACE_API_PREFIX)) {
                        continue;
                    }
                    io.swagger.v3.oas.models.PathItem item = e.getValue();
                    if (item == null) {
                        continue;
                    }
                    for (io.swagger.v3.oas.models.Operation op : item.readOperations()) {
                        if (op == null) {
                            continue;
                        }
                        String desc = op.getDescription();
                        if (desc == null || desc.isBlank()) {
                            op.setDescription(WORKSPACE_FORWARDED_HINT);
                        } else if (!desc.contains(WORKSPACE_FORWARDED_HINT)) {
                            op.setDescription(desc + "\n\n" + WORKSPACE_FORWARDED_HINT);
                        }
                    }
                }
            }

            // 2) 给 workspace 相关 tag 增加说明（让分组页也能看到提示）
            List<Tag> tags = openApi.getTags();
            if (tags != null) {
                for (Tag t : tags) {
                    if (t == null || t.getName() == null) {
                        continue;
                    }
                    // 现有 tag 命名都是 “Fun AI Workspace ...”
                    if (!t.getName().startsWith("Fun AI Workspace")) {
                        continue;
                    }
                    String desc = t.getDescription();
                    if (desc == null || desc.isBlank()) {
                        t.setDescription(WORKSPACE_FORWARDED_HINT);
                    } else if (!desc.contains(WORKSPACE_FORWARDED_HINT)) {
                        t.setDescription(desc + "\n\n" + WORKSPACE_FORWARDED_HINT);
                    }
                }
            }
        };
    }

    /**
     * 配置 Swagger UI 路径重定向
     * 确保访问 /swagger-ui/ 时能正确重定向到 /swagger-ui/index.html
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 将 /swagger-ui/ 重定向到 /swagger-ui/index.html
        registry.addRedirectViewController("/swagger-ui/", "/swagger-ui/index.html");
        // 兼容旧路径
        registry.addRedirectViewController("/swagger-ui.html", "/swagger-ui/index.html");
    }
}