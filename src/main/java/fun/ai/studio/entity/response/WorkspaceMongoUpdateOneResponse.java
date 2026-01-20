package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class WorkspaceMongoUpdateOneResponse {
    private Long userId;
    private Long appId;
    private String dbName;
    private String collection;
    private Boolean acknowledged;
    private Integer matchedCount;
    private Integer modifiedCount;
    /**
     * upsertedId：若为 ObjectId，会被规范化为 hex 字符串。
     */
    private String upsertedId;
}


