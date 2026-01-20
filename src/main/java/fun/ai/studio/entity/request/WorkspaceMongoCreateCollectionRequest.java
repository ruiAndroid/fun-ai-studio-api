package fun.ai.studio.entity.request;

import lombok.Data;

import java.util.List;

@Data
public class WorkspaceMongoCreateCollectionRequest {
    private String collection;

    /**
     * 可选：字段定义（用于生成 Mongo validator($jsonSchema)）
     */
    private List<Field> fields;

    /**
     * 可选：是否严格校验（true: validationAction=error + validationLevel=strict；false: warn+moderate）
     */
    private Boolean strict;

    @Data
    public static class Field {
        private String name;
        /**
         * 支持：string/number/int/long/bool/date/objectId/object/array（大小写不敏感）
         */
        private String type;
        /**
         * 可选：默认值（仅用于 schema 描述，不参与校验）
         */
        private Object defaultValue;
        /**
         * 可选：注释/描述（写入 schema.description）
         */
        private String comment;
        /**
         * 是否必填（加入 required）
         */
        private Boolean required;
        /**
         * 是否允许为 null（默认 true：bsonType 会包含 "null"）
         */
        private Boolean nullable;
    }
}


