package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class WorkspaceMongoDeleteOneResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private String collection;
    private Boolean acknowledged;
    private Integer deletedCount;
}


