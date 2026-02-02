package fun.ai.studio.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
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
     * 多机部署提示（现网 6 台）：Workspace 接口对外仍由 API 服务器（入口）展示/鉴权，但会转发到 workspace-dev 的 workspace-node 执行。
     * <p>
     * 注意：这里的文案刻意写成“在多机部署时”，以兼容单机/本地开发环境。
     */
    private static final String WORKSPACE_FORWARDED_HINT =
            "【多机部署提示（现网 6 台）】该接口在 API 服务器（入口）上展示/鉴权；在多机部署时会通过 workspace-node-proxy 转发到 workspace-dev（workspace-node，7001）执行。\n\n"
            + "【常见错误提示】如果你看到 code=502，通常表示“转发链路未生效或 workspace-dev 不可用”。请依次检查：\n"
            + "1) API 配置：workspace-node-proxy.enabled=true\n"
            + "2) API -> workspace-dev:7001 的网络/安全组是否放行（见 /doc/domains/server/security-groups.md）\n"
            + "3) shared-secret 是否一致（API：workspace-node-proxy.shared-secret 与 workspace-dev：workspace-node.internal.shared-secret）\n"
            + "4) workspace-dev 的 workspace-node 服务是否在线（7001）\n"
            + "5) 若启用 workspace 节点注册表：检查 /admin/nodes.html 或 /admin/nodes-admin.html?mode=workspace 是否显示节点 healthy";

    @Value("${funai.siteBaseUrl:}")
    private String siteBaseUrl;

    @Bean
    public OpenAPI customOpenAPI() {
        OpenAPI openApi = new OpenAPI()
                .info(new Info()
                        .title("FunAiStudioApi")
                        .description("""
                                风行Ai创作平台-Api接口文档

                                【现网 6 台部署总览】
                                - API（入口 / Control Plane）：对外入口（网关/Nginx 80/443）、业务 API（fun-ai-studio-api）、MySQL（按你们实际可同机/独立）；负责鉴权/授权与业务编排。
                                - Workspace-dev（开发容器节点）：workspace-node(7001) + Nginx(/ws) + 用户 workspace 容器 + verdaccio/npm 缓存 + workspace 落盘目录。
                                - Deploy（发布控制面）：fun-ai-studio-deploy(7002)，维护 Job 队列与 runtime 节点注册表/选址。
                                - Runner（执行面）：fun-ai-studio-runner(Python)，轮询 claim 任务并执行构建/部署动作。
                                - Runtime（运行态，可多台横向扩容）：runtime-agent(7005) + Docker/Podman + 网关(80/443)，承载用户应用容器并对外暴露 /runtime/{appId}/...
                                - Git（源码真相源）：Gitea（103；Runner 用 SSH 拉代码构建，Workspace push 源码）

                                【接口/流量说明】
                                - `/api/fun-ai/workspace/**`：对外仍由 API 暴露（本 Swagger 可见），多机部署时会转发到 workspace-dev 的 workspace-node 执行。
                                - `/preview/{appId}/...`：用户预览入口，公网入口通常先到 API 网关/Nginx，再转发到 workspace-dev Nginx，再反代到该用户容器 hostPort。
                                - `/api/fun-ai/deploy/**`：用户创建部署任务入口（只访问 API）；API 内部调用 Deploy(7002) 创建 Job，Runner 领取执行，Runtime 对外提供 `/runtime/{appId}/...`。

                                【节点管理（运维）】
                                - 入口页：`/admin/nodes.html#token={{adminToken}}`
                                - Workspace 节点：`/admin/nodes-admin.html?mode=workspace#token={{adminToken}}`
                                - Deploy 节点：`/admin/nodes-admin.html?mode=deploy#token={{adminToken}}`（需启用 deploy-proxy）
                                - 鉴权：Header `X-Admin-Token` + 来源 IP 白名单（见 `funai.admin.*` 配置）

                                【文档入口（建议收藏）】
                                - 总目录：`/doc/`
                                - 6 台模式安全组矩阵：`/doc/domains/server/security-groups.md`
                                - Deploy/Runner/Runtime 架构：`/doc/domains/deploy/architecture.md`
                                - Deploy/Runner/Runtime 扩容落地：`/doc/domains/server/scaling-deploy-runtime.md`

                                【排障提示】
                                - workspace 容器/端口池/运行日志/verdaccio/npm 安装问题：优先在 workspace-dev 侧排查。
                                """)
                        .version("1.0")
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")))
                .externalDocs(new ExternalDocumentation()
                        .description("部署/架构/运维文档（/doc）")
                        .url("/doc/"))
                .addSecurityItem(new SecurityRequirement().addList("Authorization"))
                .components(new Components()
                        .addSecuritySchemes("Authorization",
                                new SecurityScheme()
                                        .name("Authorization")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
        String baseUrl = normalizeBaseUrl(siteBaseUrl);
        if (baseUrl != null && !baseUrl.isBlank()) {
            openApi.addServersItem(new Server().url(baseUrl));
        }
        return openApi;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
    
    /**
     * 为 Swagger 中的 workspace 接口自动追加“多机部署转发”提示，避免访问不到 workspace-dev Swagger 时信息缺失。
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