package fun.ai.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import fun.ai.studio.entity.VerificationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VerificationCodeMapper extends BaseMapper<VerificationCode> {

    /**
     * 查询某邮箱最新未使用的验证码
     */
    @Select("SELECT * FROM fun_ai_verification_code WHERE email = #{email} AND type = #{type} AND used = 0 ORDER BY create_time DESC LIMIT 1")
    VerificationCode findLatestByEmail(@Param("email") String email, @Param("type") Integer type);

    /**
     * 查询某用户某邮箱有效验证码
     */
    @Select("SELECT * FROM fun_ai_verification_code WHERE user_id = #{userId} AND email = #{email} AND type = #{type} AND used = 0 AND expired_time > NOW() AND error_count < 3 ORDER BY create_time DESC LIMIT 1")
    VerificationCode findValidCode(@Param("userId") Long userId, @Param("email") String email, @Param("type") Integer type);

    /**
     * 查询某邮箱有效验证码（注册时用户不存在，无需userId）
     */
    @Select("SELECT * FROM fun_ai_verification_code WHERE email = #{email} AND type = #{type} AND used = 0 AND expired_time > NOW() AND error_count < 3 ORDER BY create_time DESC LIMIT 1")
    VerificationCode findValidCodeByEmail(@Param("email") String email, @Param("type") Integer type);

    /**
     * 增加错误次数
     */
    @Update("UPDATE fun_ai_verification_code SET error_count = error_count + 1 WHERE id = #{id}")
    int incrementErrorCount(@Param("id") Long id);

    /**
     * 标记为已使用
     */
    @Update("UPDATE fun_ai_verification_code SET used = 1 WHERE id = #{id}")
    int markAsUsed(@Param("id") Long id);
}
