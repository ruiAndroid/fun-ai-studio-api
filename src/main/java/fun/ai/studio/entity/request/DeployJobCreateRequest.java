package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * /api/fun-ai/deploy/job/create 请求体（可选）
 *
 * 前端推荐：只传 userId/appId（请求体可不传或传 {}）
 */
public class DeployJobCreateRequest {
    @Schema(description = "（可选）应用容器端口（默认 3000）", example = "3000")
    private Integer containerPort;

    @Schema(description = "（可选）Git ref（branch/tag/commit），默认 main", example = "main")
    private String gitRef;

    @Schema(description = "（可选）对外访问路径前缀，默认 /runtime/{appId}", example = "/runtime/20000411")
    private String basePath;

    @Schema(description = "（可选）镜像 tag（Runner build 时使用），默认 latest", example = "latest")
    private String imageTag;

    @Schema(description = "（可选）若你已经有现成镜像，可直接指定；指定后 Runner 会跳过 build/push", example = "crpi-xxx.cn-hangzhou.personal.cr.aliyuncs.com/funaistudio/u10000021-app20000411:latest")
    private String image;

    public Integer getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(Integer containerPort) {
        this.containerPort = containerPort;
    }

    public String getGitRef() {
        return gitRef;
    }

    public void setGitRef(String gitRef) {
        this.gitRef = gitRef;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getImageTag() {
        return imageTag;
    }

    public void setImageTag(String imageTag) {
        this.imageTag = imageTag;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}


