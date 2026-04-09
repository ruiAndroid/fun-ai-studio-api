package fun.ai.studio.config;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "funai.oss")
@Schema(description = "阿里云OSS配置")
public class OssProperties {

    @Schema(description = "OSS endpoint")
    private String endpoint = "oss-cn-beijing.aliyuncs.com";

    @Schema(description = "OSS bucket名称")
    private String bucketName = "fun-ai-studio";

    @Schema(description = "存储路径前缀")
    private String keyPrefix = "feedback";

    @Schema(description = "CDN域名（可选）")
    private String domain;

    @Schema(description = "OSS accessKeyId（从环境变量读取）")
    private String accessKeyId;

    @Schema(description = "OSS accessKeySecret（从环境变量读取）")
    private String accessKeySecret;
}
