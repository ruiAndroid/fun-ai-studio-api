package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class WorkspaceMongoUpdateByIdRequest {
    private String collection;
    private String id;
    /**
     * update 文档（JSON/EJSON），例如 {"$set":{"name":"new"}}。
     */
    private String update;
    private Boolean upsert;
}


