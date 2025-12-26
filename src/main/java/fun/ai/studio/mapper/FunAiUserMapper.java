package fun.ai.studio.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import fun.ai.studio.entity.FunAiUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FunAiUserMapper extends BaseMapper<FunAiUser> {
}