package fun.ai.studio.service;

import com.baomidou.mybatisplus.extension.service.IService;
import fun.ai.studio.entity.Feedback;
import fun.ai.studio.entity.request.FeedbackCreateRequest;
import fun.ai.studio.entity.request.FeedbackPageRequest;
import fun.ai.studio.entity.request.FeedbackReplyRequest;
import fun.ai.studio.entity.response.FeedbackPageResponse;
import fun.ai.studio.entity.response.FeedbackResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FeedbackService extends IService<Feedback> {

    /**
     * 用户提交反馈
     * @param request 创建请求
     * @return 反馈响应
     */
    FeedbackResponse createFeedback(FeedbackCreateRequest request);

    /**
     * 用户查看自己的反馈列表
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分页响应
     */
    FeedbackPageResponse getMyFeedbacks(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 管理员分页查询所有反馈
     * @param request 分页请求
     * @return 分页响应
     */
    FeedbackPageResponse getAllFeedbacks(FeedbackPageRequest request);

    /**
     * 管理员查看反馈详情
     * @param feedbackId 反馈ID
     * @return 反馈响应
     */
    FeedbackResponse getFeedbackDetailForAdmin(Long feedbackId);

    /**
     * 管理员回复反馈
     * @param request 回复请求
     * @return 反馈响应
     */
    FeedbackResponse replyFeedback(FeedbackReplyRequest request);

    /**
     * 上传反馈图片
     * @param files 图片文件列表
     * @return 访问URL列表
     */
    List<String> uploadImages(List<MultipartFile> files);
}
