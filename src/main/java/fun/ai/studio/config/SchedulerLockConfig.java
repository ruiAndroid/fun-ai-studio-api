package fun.ai.studio.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Distributed lock for @Scheduled jobs.
 *
 * <p>Why: in multi-instance deployments (2+ API servers), @Scheduled would otherwise run on every node.</p>
 *
 * <p>Lock storage: MySQL table {@code shedlock} (created via db init script in resources).</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // Avoid clock drift between machines by using DB time.
                        .usingDbTime()
                        .build()
        );
    }
}


