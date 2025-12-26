package fun.ai.studio.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import fun.ai.studio.entity.FunAiApp;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI应用Mapper接口
 */
@Mapper
public interface FunAiAppMapper extends BaseMapper<FunAiApp> {
    // 可以添加自定义的查询方法
}
