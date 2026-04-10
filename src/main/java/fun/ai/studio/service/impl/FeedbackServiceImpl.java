package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.entity.Feedback;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.entity.request.FeedbackCreateRequest;
import fun.ai.studio.entity.request.FeedbackPageRequest;
import fun.ai.studio.entity.request.FeedbackReplyRequest;
import fun.ai.studio.entity.response.FeedbackPageResponse;
import fun.ai.studio.entity.response.FeedbackResponse;
import fun.ai.studio.mapper.FeedbackMapper;
import fun.ai.studio.service.FeedbackService;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiUserService;
import fun.ai.studio.util.OssTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements FeedbackService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackServiceImpl.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_IMAGE_COUNT = 9;

    private final FunAiUserService funAiUserService;
    private final FunAiAppService funAiAppService;
    private final OssTemplate ossTemplate;

    public FeedbackServiceImpl(FunAiUserService funAiUserService,
                                FunAiAppService funAiAppService,
                                OssTemplate ossTemplate) {
        this.funAiUserService = funAiUserService;
        this.funAiAppService = funAiAppService;
        this.ossTemplate = ossTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackResponse createFeedback(FeedbackCreateRequest request) {
        // 校验用户存在
        FunAiUser user = funAiUserService.getById(request.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 校验应用存在
        FunAiApp app = funAiAppService.getById(request.getAppId());
        if (app == null) {
            throw new IllegalArgumentException("应用不存在");
        }

        // 校验图片数量
        if (request.getImages() != null && request.getImages().size() > MAX_IMAGE_COUNT) {
            throw new IllegalArgumentException("最多上传" + MAX_IMAGE_COUNT + "张图片");
        }

        // 上传图片
        List<String> imageUrls = new ArrayList<>();
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            imageUrls = uploadImages(request.getImages());
        }

        // 构建反馈实体
        Feedback feedback = new Feedback();
        feedback.setUserId(request.getUserId());
        feedback.setAppId(request.getAppId());
        feedback.setTitle(request.getTitle());
        feedback.setContent(request.getContent());
        feedback.setStatus(0); // 待处理
        feedback.setUser(user);
        feedback.setApp(app);

        // 图片URL转JSON存储
        if (!imageUrls.isEmpty()) {
            try {
                feedback.setImages(objectMapper.writeValueAsString(imageUrls));
            } catch (JsonProcessingException e) {
                logger.error("图片URL序列化失败", e);
                throw new RuntimeException("图片URL序列化失败");
            }
        }

        // 保存
        if (!save(feedback)) {
            throw new RuntimeException("保存反馈失败");
        }

        feedback.setImageList(imageUrls);
        return convertToResponse(feedback);
    }

    @Override
    public FeedbackPageResponse getMyFeedbacks(Long userId, Integer pageNum, Integer pageSize) {
        Page<Feedback> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Feedback> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).orderByDesc("create_time");
        Page<Feedback> result = page(page, queryWrapper);

        // 填充应用信息
        List<Long> appIds = result.getRecords().stream()
                .map(Feedback::getAppId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, FunAiApp> appMap = appIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : funAiAppService.listByIds(appIds).stream()
                        .collect(Collectors.toMap(FunAiApp::getId, a -> a));

        List<FeedbackResponse> list = result.getRecords().stream()
                .peek(f -> f.setApp(appMap.get(f.getAppId())))
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        FeedbackPageResponse response = new FeedbackPageResponse();
        response.setTotal(result.getTotal());
        response.setPageNum(pageNum);
        response.setPageSize(pageSize);
        response.setList(list);
        return response;
    }

    @Override
    public FeedbackPageResponse getAllFeedbacks(FeedbackPageRequest request) {
        Page<Feedback> page = new Page<>(request.getPageNum(), request.getPageSize());
        com.baomidou.mybatisplus.core.metadata.IPage<Feedback> result = baseMapper.selectFeedbackPage(page, request.getStatus(), request.getKeyword());

        // 填充用户和应用信息
        List<Long> userIds = result.getRecords().stream()
                .map(Feedback::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, FunAiUser> userMap = userIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : funAiUserService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(FunAiUser::getId, u -> u));

        List<Long> appIds = result.getRecords().stream()
                .map(Feedback::getAppId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, FunAiApp> appMap = appIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : funAiAppService.listByIds(appIds).stream()
                        .collect(Collectors.toMap(FunAiApp::getId, a -> a));

        List<FeedbackResponse> list = result.getRecords().stream()
                .peek(f -> {
                    f.setUser(userMap.get(f.getUserId()));
                    f.setApp(appMap.get(f.getAppId()));
                })
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        FeedbackPageResponse response = new FeedbackPageResponse();
        response.setTotal(result.getTotal());
        response.setPageNum(request.getPageNum());
        response.setPageSize(request.getPageSize());
        response.setList(list);
        return response;
    }

    @Override
    public FeedbackResponse getFeedbackDetailForAdmin(Long feedbackId) {
        Feedback feedback = getById(feedbackId);
        if (feedback == null) {
            throw new IllegalArgumentException("反馈不存在");
        }

        FunAiUser user = funAiUserService.getById(feedback.getUserId());
        FunAiApp app = funAiAppService.getById(feedback.getAppId());
        feedback.setUser(user);
        feedback.setApp(app);

        // 管理员查看时，更新状态为"已查看"（如果当前是待处理）
        if (feedback.getStatus() != null && feedback.getStatus() == 0) {
            feedback.setStatus(1); // 处理中
            updateById(feedback);
        }

        return convertToResponse(feedback);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackResponse replyFeedback(FeedbackReplyRequest request) {
        Feedback feedback = getById(request.getFeedbackId());
        if (feedback == null) {
            throw new IllegalArgumentException("反馈不存在");
        }

        feedback.setReply(request.getReply());
        feedback.setReplyTime(LocalDateTime.now());
        feedback.setStatus(request.getStatus());

        if (!updateById(feedback)) {
            throw new RuntimeException("回复保存失败");
        }

        FunAiUser user = funAiUserService.getById(feedback.getUserId());
        FunAiApp app = funAiAppService.getById(feedback.getAppId());
        feedback.setUser(user);
        feedback.setApp(app);
        return convertToResponse(feedback);
    }

    @Override
    public List<String> uploadImages(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            // 校验文件类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                throw new IllegalArgumentException("只能上传图片文件");
            }

            // 校验文件大小（例如最大5MB）
            if (file.getSize() > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("图片大小不能超过5MB");
            }

            // 上传到OSS
            String url = ossTemplate.uploadFeedbackImage(file);
            urls.add(url);
        }

        return urls;
    }

    /**
     * 将实体转换为响应DTO
     */
    private FeedbackResponse convertToResponse(Feedback feedback) {
        FeedbackResponse response = new FeedbackResponse();
        response.setId(feedback.getId());
        response.setUserId(feedback.getUserId());
        response.setUser(feedback.getUser());
        response.setAppId(feedback.getAppId());
        response.setApp(feedback.getApp());
        response.setTitle(feedback.getTitle());
        response.setContent(feedback.getContent());
        response.setStatus(feedback.getStatus());
        response.setStatusDesc(getStatusDesc(feedback.getStatus()));
        response.setReply(feedback.getReply());
        response.setReplyTime(feedback.getReplyTime());
        response.setCreateTime(feedback.getCreateTime());
        response.setUpdateTime(feedback.getUpdateTime());

        // 解析图片JSON
        if (feedback.getImages() != null && !feedback.getImages().isEmpty()) {
            try {
                response.setImages(objectMapper.readValue(feedback.getImages(),
                        new TypeReference<List<String>>() {}));
            } catch (JsonProcessingException e) {
                logger.warn("图片JSON解析失败: {}", feedback.getImages());
                response.setImages(new ArrayList<>());
            }
        } else {
            response.setImages(new ArrayList<>());
        }

        return response;
    }

    private String getStatusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "待处理";
            case 1 -> "处理中";
            case 2 -> "已处理";
            default -> "未知";
        };
    }
}
