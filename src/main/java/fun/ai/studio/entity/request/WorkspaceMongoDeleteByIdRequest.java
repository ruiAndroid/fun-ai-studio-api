package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class WorkspaceMongoDeleteByIdRequest {
    private String collection;
    private String id;
}


