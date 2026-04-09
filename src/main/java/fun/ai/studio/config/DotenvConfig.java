package fun.ai.studio.config;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 加载 .env 文件到系统环境变量
 */
@Configuration
public class DotenvConfig {

    private static final Logger logger = LoggerFactory.getLogger(DotenvConfig.class);

    @PostConstruct
    public void loadDotenv() {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .ignoreIfMalformed()
                    .ignoreIfMissing()
                    .load();

            dotenv.entries().forEach(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                if (System.getProperty(key) == null && System.getenv(key) == null) {
                    System.setProperty(key, value);
                    logger.info("从 .env 加载: {} = {}", key, maskValue(key, value));
                }
            });
        } catch (Exception e) {
            logger.warn("加载 .env 文件失败: {}", e.getMessage());
        }
    }

    private String maskValue(String key, String value) {
        if (key.toLowerCase().contains("secret") || key.toLowerCase().contains("password")) {
            return "****";
        }
        return value;
    }
}
