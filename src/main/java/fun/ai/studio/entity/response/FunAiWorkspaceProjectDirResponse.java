package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class FunAiWorkspaceProjectDirResponse {
    private Long userId;
    private String projectId;

    private String hostProjectDir;
    private String containerProjectDir;
}


