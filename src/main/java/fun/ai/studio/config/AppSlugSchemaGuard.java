package fun.ai.studio.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * 轻量 schema guard：当前项目未接入 Flyway/Liquibase，这里在启动期补齐 app_slug 列与唯一索引。
 */
@Component
public class AppSlugSchemaGuard {

    private static final Logger log = LoggerFactory.getLogger(AppSlugSchemaGuard.class);
    private static final String TABLE_NAME = "fun_ai_app";
    private static final String COLUMN_NAME = "app_slug";
    private static final String INDEX_NAME = "uk_fun_ai_app_app_slug";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public AppSlugSchemaGuard(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureSchema() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            if (!tableExists(meta, TABLE_NAME)) {
                log.info("skip appSlug schema guard: table {} not found", TABLE_NAME);
                return;
            }

            if (!columnExists(meta, TABLE_NAME, COLUMN_NAME)) {
                log.info("adding missing column {}.{}", TABLE_NAME, COLUMN_NAME);
                jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME + " VARCHAR(40) NULL");
            }

            if (!indexExists(meta, TABLE_NAME, INDEX_NAME)) {
                log.info("adding missing unique index {} on {}({})", INDEX_NAME, TABLE_NAME, COLUMN_NAME);
                jdbcTemplate.execute("CREATE UNIQUE INDEX " + INDEX_NAME + " ON " + TABLE_NAME + " (" + COLUMN_NAME + ")");
            }
        } catch (Exception e) {
            log.warn("appSlug schema guard skipped due to error: {}", e.getMessage(), e);
        }
    }

    private boolean tableExists(DatabaseMetaData meta, String tableName) throws Exception {
        try (ResultSet rs = meta.getTables(null, null, tableName, null)) {
            if (rs.next()) return true;
        }
        try (ResultSet rs = meta.getTables(null, null, tableName.toUpperCase(), null)) {
            return rs.next();
        }
    }

    private boolean columnExists(DatabaseMetaData meta, String tableName, String columnName) throws Exception {
        try (ResultSet rs = meta.getColumns(null, null, tableName, columnName)) {
            if (rs.next()) return true;
        }
        try (ResultSet rs = meta.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return rs.next();
        }
    }

    private boolean indexExists(DatabaseMetaData meta, String tableName, String indexName) throws Exception {
        try (ResultSet rs = meta.getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, tableName.toUpperCase(), false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
