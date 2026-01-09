# 项目目录结构建议（逐步演进）

这份文档用于解释“当前结构为什么看起来有点乱”，以及一条低风险的整理路径。

## 现状

- Workspace 相关实现已经按子域拆分到 `fun.ai.studio.workspace.*`（很好）
- 但 DTO 仍散落在 `fun.ai.studio.entity.request|response`，导致：
  - `entity` 包既放“持久化实体”，又放“接口 DTO”，概念混杂
  - Workspace DTO 和 App DTO 混在同一层级，难以按域检索
- 文档目前集中在 `src/main/resources/doc/`，但缺少按子系统拆分的说明与索引

## 推荐的目标结构（不要求一次改完）

### A. 文档（低风险，建议立即整理）

- `src/main/resources/doc/README.md`：总索引
- `src/main/resources/doc/domains/`：按子系统拆分文档

### B. Java 包（中风险，建议分阶段）

建议将“持久化实体”和“接口 DTO”拆开：

- `fun.ai.studio.entity`：仅放 DB 实体（MyBatis/JPA 映射）
- `fun.ai.studio.dto.*`：放接口 DTO
  - `fun.ai.studio.dto.app.*`
  - `fun.ai.studio.dto.workspace.*`

迁移策略（推荐渐进）：

- 新增 DTO 一律放 `dto`，旧的先不动
- 当某个域要大改时，再把该域的 request/response 批量迁移，并一次性替换 import

## 为什么不建议一次性大迁移

大范围 move Java 文件会带来：

- import 全量变化
- 可能触发序列化/反序列化注解路径差异
- 影响 Swagger 分组/扫描（若存在自定义规则）

所以更推荐“文档先行 + 新增代码遵循新结构 + 旧代码渐进迁移”的策略。


