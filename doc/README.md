# 说明

本仓库有两类“doc”：

- `src/main/resources/doc/`：**线上文档目录**（会打进 JAR），由 `DocController` 通过 `ClassPathResource("doc/...")` 提供访问：
  - `/doc/`（默认渲染 `README.md`）
  - `/doc/{name}`（渲染 Markdown）
  - `/doc/raw/{name}`（原始 Markdown）
- `doc/sql/`：**开发/运维用 SQL 脚本**（不打进 JAR）

为避免重复维护，根目录 `doc/` 不再存放线上 Markdown 文档（请以 `src/main/resources/doc/` 为准）。
