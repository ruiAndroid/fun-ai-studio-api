package fun.ai.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import fun.ai.studio.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {

    /**
     * 分页查询反馈列表（管理员）
     */
    IPage<Feedback> selectFeedbackPage(Page<Feedback> page,
                                      @Param("status") Integer status,
                                      @Param("keyword") String keyword);

    /**
     * 查询用户自己的反馈列表
     */
    IPage<Feedback> selectByUserId(Page<Feedback> page, @Param("userId") Long userId);
}
