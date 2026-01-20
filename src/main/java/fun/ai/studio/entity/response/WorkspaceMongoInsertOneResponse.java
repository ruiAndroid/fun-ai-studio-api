package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class WorkspaceMongoInsertOneResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private String collection;
    private Boolean acknowledged;
    /**
     * insertedId：若为 ObjectId，会被规范化为 hex 字符串。
     */
    private String insertedId;
}


