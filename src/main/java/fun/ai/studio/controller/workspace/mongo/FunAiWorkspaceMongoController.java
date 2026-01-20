package fun.ai.studio.controller.workspace.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.entity.request.WorkspaceMongoFindRequest;
import fun.ai.studio.entity.request.WorkspaceMongoCreateCollectionRequest;
import fun.ai.studio.entity.request.WorkspaceMongoInsertOneRequest;
import fun.ai.studio.entity.request.WorkspaceMongoUpdateByIdRequest;
import fun.ai.studio.entity.request.WorkspaceMongoDeleteByIdRequest;
import fun.ai.studio.entity.request.WorkspaceMongoOpRequest;
import fun.ai.studio.entity.response.FunAiWorkspaceRunMeta;
import fun.ai.studio.entity.response.WorkspaceMongoCollectionsResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDocResponse;
import fun.ai.studio.entity.response.WorkspaceMongoFindResponse;
import fun.ai.studio.entity.response.WorkspaceMongoCreateCollectionResponse;
import fun.ai.studio.entity.response.WorkspaceMongoInsertOneResponse;
import fun.ai.studio.entity.response.WorkspaceMongoUpdateOneResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDeleteOneResponse;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import fun.ai.studio.workspace.WorkspaceNodeClient;
import fun.ai.studio.workspace.mongo.WorkspaceMongoShellClient;
import fun.ai.studio.common.WorkspaceNodeProxyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Workspace Mongo Explorer（只读）
 * <p>
 * 设计目标：不暴露 27017，通过 docker exec 在容器内调用 mongosh 查询。
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/mongo")
@Tag(name = "Fun AI Workspace Mongo Explorer", 
description = "Mongo Explorer（只读）访问地址 http://{{公网ip}}/workspace-mongo.html?userId={{userId}}&appId={{appId}}#token={{token}} ")
public class FunAiWorkspaceMongoController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceMongoController.class);

    private final FunAiWorkspaceService workspaceService;
    private final FunAiAppService funAiAppService;
    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceActivityTracker activityTracker;
    private final WorkspaceNodeClient workspaceNodeClient;
    private final ObjectProvider<WorkspaceMongoShellClient> mongoShellClientProvider;
    private final ObjectMapper objectMapper;

    public FunAiWorkspaceMongoController(FunAiWorkspaceService workspaceService,
                                         FunAiAppService funAiAppService,
                                         WorkspaceProperties workspaceProperties,
                                         WorkspaceActivityTracker activityTracker,
                                         WorkspaceNodeClient workspaceNodeClient,
                                         ObjectProvider<WorkspaceMongoShellClient> mongoShellClientProvider,
                                         ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.funAiAppService = funAiAppService;
        this.workspaceProperties = workspaceProperties;
        this.activityTracker = activityTracker;
        this.workspaceNodeClient = workspaceNodeClient;
        this.mongoShellClientProvider = mongoShellClientProvider;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/collections")
    @Operation(summary = "列出集合（只读）", description = "在容器内使用 mongosh 列出 db_{appId} 的集合列表")
    public Result<WorkspaceMongoCollectionsResponse> collections(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);

            // 双机模式：由 API 服务器（小机）做 appOwned 校验后，转发到 workspace-node 执行（workspace-node 会自行校验 preview RUNNING 等条件）
            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoCollectionsResponse remote = workspaceNodeClient.mongoCollections(userId, appId);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();
            List<String> cols = mongoShellClient.listCollections(containerName, dbName);

            WorkspaceMongoCollectionsResponse resp = new WorkspaceMongoCollectionsResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollections(cols);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo collections failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo collections failed: " + e.getMessage());
        }
    }

    @PostMapping("/find")
    @Operation(summary = "查询文档列表（只读）", description = "在容器内使用 mongosh 执行 find（带 limit/skip/sort/projection）")
    public Result<WorkspaceMongoFindResponse> find(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoFindRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            if (req == null) throw new IllegalArgumentException("body 不能为空");

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoFindResponse remote = workspaceNodeClient.mongoFind(userId, appId, req);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();
            Map<String, Object> out = mongoShellClient.find(
                    containerName,
                    dbName,
                    req.getCollection(),
                    req.getFilter(),
                    req.getProjection(),
                    req.getSort(),
                    req.getLimit(),
                    req.getSkip()
            );

            WorkspaceMongoFindResponse resp = new WorkspaceMongoFindResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setLimit(asInt(out.get("limit")));
            resp.setSkip(asInt(out.get("skip")));
            resp.setReturned(asInt(out.get("returned")));
            resp.setItems(toListOfStringObjectMap(out.get("items")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo find failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo find failed: " + e.getMessage());
        }
    }

    @GetMapping("/doc")
    @Operation(summary = "按 _id 查询单条文档（只读）", description = "id 尝试按 ObjectId 解析，失败则按字符串 _id 查询")
    public Result<WorkspaceMongoDocResponse> doc(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "集合名", required = true) @RequestParam String collection,
            @Parameter(description = "文档 _id（ObjectId hex 或 string）", required = true) @RequestParam String id
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoDocResponse remote = workspaceNodeClient.mongoDoc(userId, appId, collection, id);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();
            Map<String, Object> out = mongoShellClient.findOneById(containerName, dbName, collection, id);

            WorkspaceMongoDocResponse resp = new WorkspaceMongoDocResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setId(asString(out.get("id")));
            resp.setDoc(toStringObjectMapOrNull(out.get("doc")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo doc failed: userId={}, appId={}, collection={}, id={}, error={}",
                    userId, appId, collection, id, e.getMessage(), e);
            return Result.error("mongo doc failed: " + e.getMessage());
        }
    }

    @PostMapping("/create-collection")
    @Operation(summary = "创建集合（写）", description = "在容器内使用 mongosh 创建集合（同 appId 仅允许访问 dbNamePrefix+appId）")
    public Result<WorkspaceMongoCreateCollectionResponse> createCollection(
            @RequestParam Long userId,
            @RequestParam Long appId,
            @RequestBody WorkspaceMongoCreateCollectionRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            if (req == null || !StringUtils.hasText(req.getCollection())) throw new IllegalArgumentException("collection 不能为空");

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoCreateCollectionResponse remote = workspaceNodeClient.mongoCreateCollection(userId, appId, req);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();

            // 可选：根据 fields 生成 validator($jsonSchema)
            String optionsJson = null;
            if (req.getFields() != null && !req.getFields().isEmpty()) {
                optionsJson = buildCreateCollectionOptionsJson(req);
            }
            Map<String, Object> out = (optionsJson == null)
                    ? mongoShellClient.createCollection(containerName, dbName, req.getCollection())
                    : mongoShellClient.createCollection(containerName, dbName, req.getCollection(), optionsJson);

            WorkspaceMongoCreateCollectionResponse resp = new WorkspaceMongoCreateCollectionResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setCreated(asBool(out.get("created")));
            resp.setMessage(asString(out.get("message")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo create-collection failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo create-collection failed: " + e.getMessage());
        }
    }

    private String buildCreateCollectionOptionsJson(WorkspaceMongoCreateCollectionRequest req) {
        // Mongo createCollection options: { validator: { $jsonSchema: ... }, validationAction, validationLevel }
        boolean strict = req.getStrict() != null && req.getStrict();

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("bsonType", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Set<String> required = new HashSet<>();

        for (WorkspaceMongoCreateCollectionRequest.Field f : req.getFields()) {
            if (f == null) continue;
            String name = f.getName() == null ? "" : f.getName().trim();
            if (name.isEmpty()) continue;
            String type = f.getType() == null ? "" : f.getType().trim().toLowerCase();
            boolean nullable = (f.getNullable() == null) || f.getNullable();
            String bsonType = mapToBsonType(type);

            Map<String, Object> def = new LinkedHashMap<>();
            if (nullable) {
                def.put("bsonType", List.of(bsonType, "null"));
            } else {
                def.put("bsonType", bsonType);
            }
            props.put(name, def);

            if (f.getRequired() != null && f.getRequired()) {
                required.add(name);
            }
        }

        jsonSchema.put("properties", props);
        if (!required.isEmpty()) {
            jsonSchema.put("required", required.stream().sorted().toList());
        }
        // 默认允许额外字段（更符合 Mongo “灵活”），避免前端未列出字段导致写入失败
        jsonSchema.put("additionalProperties", true);

        Map<String, Object> validator = new LinkedHashMap<>();
        validator.put("$jsonSchema", jsonSchema);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("validator", validator);
        options.put("validationAction", strict ? "error" : "warn");
        options.put("validationLevel", strict ? "strict" : "moderate");

        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception e) {
            throw new IllegalArgumentException("fields 生成 schema 失败：" + e.getMessage());
        }
    }

    private static String mapToBsonType(String t) {
        if (t == null) return "string";
        String s = t.trim().toLowerCase();
        if (s.isEmpty()) return "string";
        return switch (s) {
            case "string", "str" -> "string";
            case "number", "double", "float", "decimal" -> "double";
            case "int", "int32", "integer" -> "int";
            case "long", "int64" -> "long";
            case "bool", "boolean" -> "bool";
            case "date", "datetime", "timestamp" -> "date";
            case "objectid", "oid" -> "objectId";
            case "object", "map" -> "object";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    @PostMapping("/insert-one")
    @Operation(summary = "插入单条（写）", description = "在容器内使用 mongosh insertOne（doc 为 JSON/EJSON）")
    public Result<WorkspaceMongoInsertOneResponse> insertOne(
            @RequestParam Long userId,
            @RequestParam Long appId,
            @RequestBody WorkspaceMongoInsertOneRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            if (req == null) throw new IllegalArgumentException("body 不能为空");
            if (!StringUtils.hasText(req.getCollection())) throw new IllegalArgumentException("collection 不能为空");
            if (!StringUtils.hasText(req.getDoc())) throw new IllegalArgumentException("doc 不能为空");

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoInsertOneResponse remote = workspaceNodeClient.mongoInsertOne(userId, appId, req);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();
            Map<String, Object> out = mongoShellClient.insertOne(containerName, dbName, req.getCollection(), req.getDoc());

            WorkspaceMongoInsertOneResponse resp = new WorkspaceMongoInsertOneResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setAcknowledged(asBool(out.get("acknowledged")));
            resp.setInsertedId(normalizeId(out.get("insertedId")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo insert-one failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo insert-one failed: " + e.getMessage());
        }
    }

    @PostMapping("/update-by-id")
    @Operation(summary = "按 _id 更新（写）", description = "在容器内使用 mongosh updateOne({_id}, update)；update 为 JSON/EJSON（建议 {$set:{...}}）")
    public Result<WorkspaceMongoUpdateOneResponse> updateById(
            @RequestParam Long userId,
            @RequestParam Long appId,
            @RequestBody WorkspaceMongoUpdateByIdRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            if (req == null) throw new IllegalArgumentException("body 不能为空");
            if (!StringUtils.hasText(req.getCollection())) throw new IllegalArgumentException("collection 不能为空");
            if (!StringUtils.hasText(req.getId())) throw new IllegalArgumentException("id 不能为空");
            if (!StringUtils.hasText(req.getUpdate())) throw new IllegalArgumentException("update 不能为空");

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoUpdateOneResponse remote = workspaceNodeClient.mongoUpdateById(userId, appId, req);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();
            Map<String, Object> out = mongoShellClient.updateOneById(containerName, dbName, req.getCollection(), req.getId(), req.getUpdate(), req.getUpsert());

            WorkspaceMongoUpdateOneResponse resp = new WorkspaceMongoUpdateOneResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setAcknowledged(asBool(out.get("acknowledged")));
            resp.setMatchedCount(asInt(out.get("matchedCount")));
            resp.setModifiedCount(asInt(out.get("modifiedCount")));
            resp.setUpsertedId(normalizeId(out.get("upsertedId")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo update-by-id failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo update-by-id failed: " + e.getMessage());
        }
    }

    @PostMapping("/delete-by-id")
    @Operation(summary = "按 _id 删除（写）", description = "在容器内使用 mongosh deleteOne({_id})")
    public Result<WorkspaceMongoDeleteOneResponse> deleteById(
            @RequestParam Long userId,
            @RequestParam Long appId,
            @RequestBody WorkspaceMongoDeleteByIdRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            if (req == null) throw new IllegalArgumentException("body 不能为空");
            if (!StringUtils.hasText(req.getCollection())) throw new IllegalArgumentException("collection 不能为空");
            if (!StringUtils.hasText(req.getId())) throw new IllegalArgumentException("id 不能为空");

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                WorkspaceMongoDeleteOneResponse remote = workspaceNodeClient.mongoDeleteById(userId, appId, req);
                return Result.success(remote);
            }

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            assertPreviewRunning(userId, appId);
            WorkspaceMongoShellClient mongoShellClient = requireMongoShellClient();
            Map<String, Object> out = mongoShellClient.deleteOneById(containerName, dbName, req.getCollection(), req.getId());

            WorkspaceMongoDeleteOneResponse resp = new WorkspaceMongoDeleteOneResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setAcknowledged(asBool(out.get("acknowledged")));
            resp.setDeletedCount(asInt(out.get("deletedCount")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo delete-by-id failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo delete-by-id failed: " + e.getMessage());
        }
    }

    @PostMapping("/op")
    @Operation(summary = "高级操作（安全 JSON 模式）", description = "以 op + 结构化字段执行（不接受任意 JS）。适合在页面里写“语句”并执行。")
    public Result<Object> op(
            @RequestParam Long userId,
            @RequestParam Long appId,
            @RequestBody WorkspaceMongoOpRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            if (req == null || !StringUtils.hasText(req.getOp())) throw new IllegalArgumentException("op 不能为空");

            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                Object remote = workspaceNodeClient.mongoOp(userId, appId, req);
                return Result.success(remote);
            }

            String op = req.getOp().trim().toLowerCase();
            if ("find".equals(op)) {
                WorkspaceMongoFindRequest fr = new WorkspaceMongoFindRequest();
                fr.setCollection(req.getCollection());
                fr.setFilter(req.getFilter());
                fr.setProjection(req.getProjection());
                fr.setSort(req.getSort());
                fr.setLimit(req.getLimit());
                fr.setSkip(req.getSkip());
                return wrap(find(userId, appId, fr));
            }
            if ("doc".equals(op)) {
                if (!StringUtils.hasText(req.getCollection())) throw new IllegalArgumentException("collection 不能为空");
                if (!StringUtils.hasText(req.getId())) throw new IllegalArgumentException("id 不能为空");
                return wrap(doc(userId, appId, req.getCollection(), req.getId()));
            }
            if ("createcollection".equals(op) || "create_collection".equals(op) || "create-collection".equals(op)) {
                WorkspaceMongoCreateCollectionRequest cr = new WorkspaceMongoCreateCollectionRequest();
                cr.setCollection(req.getCollection());
                return wrap(createCollection(userId, appId, cr));
            }
            if ("insertone".equals(op) || "insert_one".equals(op) || "insert-one".equals(op)) {
                WorkspaceMongoInsertOneRequest ir = new WorkspaceMongoInsertOneRequest();
                ir.setCollection(req.getCollection());
                ir.setDoc(req.getDoc());
                return wrap(insertOne(userId, appId, ir));
            }
            if ("updatebyid".equals(op) || "update_by_id".equals(op) || "update-by-id".equals(op)) {
                WorkspaceMongoUpdateByIdRequest ur = new WorkspaceMongoUpdateByIdRequest();
                ur.setCollection(req.getCollection());
                ur.setId(req.getId());
                ur.setUpdate(req.getUpdate());
                ur.setUpsert(req.getUpsert());
                return wrap(updateById(userId, appId, ur));
            }
            if ("deletebyid".equals(op) || "delete_by_id".equals(op) || "delete-by-id".equals(op)) {
                WorkspaceMongoDeleteByIdRequest dr = new WorkspaceMongoDeleteByIdRequest();
                dr.setCollection(req.getCollection());
                dr.setId(req.getId());
                return wrap(deleteById(userId, appId, dr));
            }

            throw new IllegalArgumentException("不支持的 op：" + req.getOp() + "（支持 find/doc/createCollection/insertOne/updateById/deleteById）");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo op failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo op failed: " + e.getMessage());
        }
    }

    private static <T> Result<Object> wrap(Result<T> r) {
        if (r == null) return Result.error("内部错误：空响应");
        Integer code = r.getCode();
        if (code == null || code != 200) {
            return Result.error(r.getMessage() == null ? "请求失败" : r.getMessage());
        }
        return Result.success(r.getData());
    }

    private void assertAppOwned(Long userId, Long appId) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }
    }

    private WorkspaceMongoShellClient requireMongoShellClient() {
        WorkspaceMongoShellClient c = mongoShellClientProvider == null ? null : mongoShellClientProvider.getIfAvailable();
        if (c == null) {
            // API 服务器（小机）裁剪模式下不加载 docker/mongosh 执行链路；该功能应由 Workspace 开发服务器（大机）workspace-node 承载
            throw new WorkspaceNodeProxyException("Mongo Explorer 相关能力已迁移到 Workspace 开发服务器（大机）容器节点（workspace-node）；请检查 API 服务器（小机）到 Workspace 开发服务器（大机）的转发链路。");
        }
        return c;
    }

    private String containerName(Long userId) {
        String prefix = workspaceProperties == null ? null : workspaceProperties.getContainerNamePrefix();
        if (!StringUtils.hasText(prefix)) prefix = "ws-u-";
        return prefix + userId;
    }

    private String dbNameForApp(Long appId) {
        String prefix = (workspaceProperties == null || workspaceProperties.getMongo() == null)
                ? null
                : workspaceProperties.getMongo().getDbNamePrefix();
        if (!StringUtils.hasText(prefix)) prefix = "db_";
        return prefix + appId;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        return null;
    }

    private static String normalizeId(Object v) {
        if (v == null) return null;
        // mongosh EJSON relaxed 下，ObjectId 常见形态：{"$oid":"..."}；这里尽量提取成字符串
        if (v instanceof Map<?, ?> m) {
            Object oid = m.get("$oid");
            if (oid != null) return String.valueOf(oid);
        }
        return String.valueOf(v);
    }

    private static List<Map<String, Object>> toListOfStringObjectMap(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        return l.stream()
                .map(FunAiWorkspaceMongoController::toStringObjectMapOrNull)
                .filter(m -> m != null && !m.isEmpty())
                .toList();
    }

    private static Map<String, Object> toStringObjectMapOrNull(Object v) {
        if (!(v instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = e.getKey() == null ? null : String.valueOf(e.getKey());
            out.put(k, e.getValue());
        }
        return out;
    }

    /**
     * 强制要求：必须先 preview（START）且处于 RUNNING，才允许进行 Mongo Explorer 查询。
     */
    private void assertPreviewRunning(Long userId, Long appId) {
        if (userId == null || appId == null) throw new IllegalArgumentException("userId/appId 不能为空");

        // 1) 容器必须 RUNNING（不做 ensure，避免“没 preview 也把容器拉起来”）
        String cStatus;
        try {
            cStatus = workspaceService.getStatus(userId).getContainerStatus();
        } catch (Exception e) {
            throw new IllegalArgumentException("请先打开项目并点击 preview");
        }
        if (cStatus == null || !"RUNNING".equalsIgnoreCase(cStatus)) {
            throw new IllegalArgumentException("请先打开项目并点击 preview（当前容器状态=" + cStatus + "）");
        }

        // 2) 运行态必须存在且为 START，且 appId 匹配
        Path metaPath = resolveHostRunMeta(userId);
        if (Files.notExists(metaPath)) {
            throw new IllegalArgumentException("请先打开项目并点击 preview（当前没有运行态元数据）");
        }
        FunAiWorkspaceRunMeta meta;
        try {
            String json = Files.readString(metaPath, StandardCharsets.UTF_8);
            meta = objectMapper.readValue(json, FunAiWorkspaceRunMeta.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("运行态元数据解析失败，请重新 preview 后重试");
        }
        if (meta == null || meta.getAppId() == null) {
            throw new IllegalArgumentException("请先打开项目并点击 preview（运行态缺少 appId）");
        }
        if (!meta.getAppId().equals(appId)) {
            throw new IllegalArgumentException("当前正在预览其它应用(appId=" + meta.getAppId() + ")，请切换到目标应用后点击 preview");
        }
        String t = meta.getType() == null ? "" : meta.getType().trim().toUpperCase();
        if (!"START".equals(t)) {
            throw new IllegalArgumentException("仅允许在 preview（START）运行态访问数据库；当前运行态=" + (t.isEmpty() ? "UNKNOWN" : t));
        }
        if (meta.getPid() == null) {
            throw new IllegalArgumentException("preview 启动中（尚未就绪），请稍后重试");
        }
    }

    private Path resolveHostRunMeta(Long userId) {
        String root = workspaceProperties == null ? null : workspaceProperties.getHostRoot();
        root = sanitizePath(root);
        if (!StringUtils.hasText(root)) {
            throw new IllegalArgumentException("workspace.hostRoot 未配置，无法读取运行态元数据");
        }
        return Paths.get(root, String.valueOf(userId), "run", "current.json");
    }

    private String sanitizePath(String p) {
        if (p == null) return null;
        return p.trim().replaceAll("^[\"']|[\"']$", "");
    }
}


