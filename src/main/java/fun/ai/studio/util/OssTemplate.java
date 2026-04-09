package fun.ai.studio.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import fun.ai.studio.config.OssProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class OssTemplate {

    private static final Logger logger = LoggerFactory.getLogger(OssTemplate.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OssProperties ossProperties;
    private OSS ossClient;

    public OssTemplate(OssProperties ossProperties) {
        this.ossProperties = ossProperties;
    }

    @PostConstruct
    public void init() {
        String accessKeyId = ossProperties.getAccessKeyId();
        String accessKeySecret = ossProperties.getAccessKeySecret();

        if (accessKeyId == null || accessKeyId.isEmpty() || accessKeySecret == null || accessKeySecret.isEmpty()) {
            logger.warn("阿里云OSS未配置密钥，环境变量 OSS_ACCESS_KEY_ID 和 OSS_ACCESS_KEY_SECRET 未设置或为空");
            return;
        }

        ossClient = new OSSClientBuilder().build(
                ossProperties.getEndpoint(),
                accessKeyId,
                accessKeySecret
        );
        logger.info("阿里云OSS客户端初始化成功");
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            logger.info("阿里云OSS客户端已关闭");
        }
    }

    /**
     * 上传反馈图片
     * @param file 图片文件
     * @return 访问URL
     */
    public String uploadFeedbackImage(MultipartFile file) {
        if (ossClient == null) {
            throw new IllegalStateException("阿里云OSS未初始化，请检查环境变量 OSS_ACCESS_KEY_ID 和 OSS_ACCESS_KEY_SECRET");
        }

        // 生成存储路径：feedback/20260409/uuid.jpg
        String datePath = LocalDate.now().format(DATE_FORMATTER);
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String objectName = ossProperties.getKeyPrefix() + "/" + datePath + "/" + UUID.randomUUID() + extension;

        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(file.getContentType());
            metadata.setContentLength(file.getSize());

            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    ossProperties.getBucketName(),
                    objectName,
                    inputStream,
                    metadata
            );

            ossClient.putObject(putObjectRequest);

            // 构建访问URL
            String url = buildUrl(objectName);
            logger.info("图片上传成功: {}, URL: {}", objectName, url);
            return url;

        } catch (IOException e) {
            logger.error("图片上传失败", e);
            throw new RuntimeException("图片上传失败: " + e.getMessage());
        }
    }

    /**
     * 删除文件
     * @param objectName 存储对象名
     */
    public void delete(String objectName) {
        if (ossClient != null) {
            ossClient.deleteObject(ossProperties.getBucketName(), objectName);
            logger.info("图片删除成功: {}", objectName);
        }
    }

    /**
     * 构建访问URL
     */
    private String buildUrl(String objectName) {
        String domain = ossProperties.getDomain();
        if (domain != null && !domain.isEmpty()) {
            // 使用自定义域名
            return domain + "/" + objectName;
        }
        // 使用默认OSS域名
        return "https://" + ossProperties.getBucketName() + "." + ossProperties.getEndpoint() + "/" + objectName;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return ".jpg";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            return ".jpg";
        }
        return filename.substring(dotIndex);
    }
}
