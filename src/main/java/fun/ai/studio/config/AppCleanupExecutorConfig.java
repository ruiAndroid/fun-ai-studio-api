package fun.ai.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AppCleanupExecutorConfig {

    /**
     * 用于 /app/delete 后台清理任务（workspace/gitea/deploy 等）。
     * - 避免阻塞 Web 线程
     * - 避免外部依赖卡住造成线程耗尽
     */
    @Bean(name = "appCleanupExecutor")
    public Executor appCleanupExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("app-cleanup-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}


