package fun.ai.studio.entity.request;

import lombok.Data;

/**
 * 高级操作（安全 JSON 模式）：
 * 不接受任意 JS，只接受结构化字段；后端根据 op 选择固定模板脚本执行。
 */
@Data
public class WorkspaceMongoOpRequest {
    /**
     * 支持：find / doc / createCollection / insertOne / updateById / deleteById
     */
    private String op;

    private String collection;
    private String id;

    // find
    private String filter;
    private String projection;
    private String sort;
    private Integer limit;
    private Integer skip;

    // insertOne
    private String doc;

    // updateById
    private String update;
    private Boolean upsert;
}


