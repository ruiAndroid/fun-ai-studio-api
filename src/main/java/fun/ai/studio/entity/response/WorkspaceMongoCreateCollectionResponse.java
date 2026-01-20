package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class WorkspaceMongoCreateCollectionResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private String collection;
    private Boolean created;
    private String message;
}


