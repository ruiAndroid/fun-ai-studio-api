package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class WorkspaceMongoInsertOneRequest {
    private String collection;
    /**
     * JSON 字符串（EJSON relaxed 也可），例如 {"name":"tom"}。
     */
    private String doc;
}


