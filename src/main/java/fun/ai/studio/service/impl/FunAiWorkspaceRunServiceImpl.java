package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import fun.ai.studio.entity.FunAiWorkspaceRun;
import fun.ai.studio.mapper.FunAiWorkspaceRunMapper;
import fun.ai.studio.service.FunAiWorkspaceRunService;
import org.springframework.stereotype.Service;

@Service
public class FunAiWorkspaceRunServiceImpl extends ServiceImpl<FunAiWorkspaceRunMapper, FunAiWorkspaceRun>
        implements FunAiWorkspaceRunService {

    @Override
    public FunAiWorkspaceRun getByUserId(Long userId) {
        if (userId == null) return null;
        QueryWrapper<FunAiWorkspaceRun> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).last("limit 1");
        return getBaseMapper().selectOne(qw);
    }

    @Override
    public void upsertByUserId(FunAiWorkspaceRun record) {
        if (record == null || record.getUserId() == null) return;
        FunAiWorkspaceRun existing = getByUserId(record.getUserId());
        if (existing == null) {
            // insert
            save(record);
        } else {
            // update
            record.setId(existing.getId());
            updateById(record);
        }
    }

    @Override
    public void touch(Long userId, Long activeAtMs) {
        if (userId == null) return;
        long ts = activeAtMs == null ? System.currentTimeMillis() : activeAtMs;
        FunAiWorkspaceRun existing = getByUserId(userId);
        if (existing == null) {
            FunAiWorkspaceRun r = new FunAiWorkspaceRun();
            r.setUserId(userId);
            r.setLastActiveAt(ts);
            save(r);
        } else {
            FunAiWorkspaceRun upd = new FunAiWorkspaceRun();
            upd.setId(existing.getId());
            upd.setUserId(userId);
            upd.setLastActiveAt(ts);
            updateById(upd);
        }
    }
}


