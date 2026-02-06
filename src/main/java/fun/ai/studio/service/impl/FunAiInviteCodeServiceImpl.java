package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import fun.ai.studio.entity.FunAiInviteCode;
import fun.ai.studio.mapper.FunAiInviteCodeMapper;
import fun.ai.studio.service.FunAiInviteCodeService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class FunAiInviteCodeServiceImpl extends ServiceImpl<FunAiInviteCodeMapper, FunAiInviteCode>
        implements FunAiInviteCodeService {

    private static final int STATUS_UNUSED = 0;
    private static final int STATUS_USED = 1;

    @Override
    public boolean isAvailable(String code) {
        String c = normalize(code);
        if (!StringUtils.hasText(c)) return false;
        return this.lambdaQuery()
                .eq(FunAiInviteCode::getCode, c)
                .eq(FunAiInviteCode::getStatus, STATUS_UNUSED)
                .count() > 0;
    }

    @Override
    public void consume(String code, Long usedByUserId) throws IllegalArgumentException {
        String c = normalize(code);
        if (!StringUtils.hasText(c)) {
            throw new IllegalArgumentException("邀请码不能为空");
        }
        if (usedByUserId == null) {
            throw new IllegalArgumentException("usedByUserId 不能为空");
        }

        boolean ok = this.lambdaUpdate()
                .eq(FunAiInviteCode::getCode, c)
                .eq(FunAiInviteCode::getStatus, STATUS_UNUSED)
                .set(FunAiInviteCode::getStatus, STATUS_USED)
                .set(FunAiInviteCode::getUsedByUserId, usedByUserId)
                .set(FunAiInviteCode::getUsedAt, LocalDateTime.now())
                .update();
        if (ok) return;

        // 失败时尽量给出更明确的原因
        FunAiInviteCode row = this.lambdaQuery()
                .eq(FunAiInviteCode::getCode, c)
                .last("limit 1")
                .one();
        if (row == null) {
            throw new IllegalArgumentException("邀请码无效");
        }
        if (row.getStatus() != null && row.getStatus() == STATUS_USED) {
            throw new IllegalArgumentException("邀请码已被使用");
        }
        throw new IllegalArgumentException("邀请码不可用");
    }

    private String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }
}

