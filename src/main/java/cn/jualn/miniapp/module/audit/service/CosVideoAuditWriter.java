package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import cn.jualn.miniapp.infrastructure.async.job.*;
import cn.jualn.miniapp.module.audit.async.CosVideoAuditPayload;
import cn.jualn.miniapp.module.audit.entity.MediaVideoAudit;
import cn.jualn.miniapp.module.audit.mapper.MediaVideoAuditMapper;
import cn.jualn.miniapp.third.cos.dto.CosVideoAuditResult;
import java.time.*;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short, fenced transactions. No network, sleeps, or publication here. */
@Service @RequiredArgsConstructor
public class CosVideoAuditWriter {
    private final MediaVideoAuditMapper rows;
    private final JobService jobs;
    public static final String JOB_TYPE="audit.cos-video.check";

    @Transactional(rollbackFor=Exception.class)
    public MediaVideoAudit reserve(MediaVideoAudit candidate) {
        rows.reserve(candidate);
        var row=rows.find(candidate.getAttachmentId(),candidate.getPolicyKey());
        if(row==null || !Objects.equals(row.getObjectKey(),candidate.getObjectKey()) || !Objects.equals(row.getSizeBytes(),candidate.getSizeBytes())) throw new IllegalStateException("Video resource identity changed");
        if("PENDING".equals(row.getState())) enqueue(row,0,now());
        return row;
    }

    @Transactional(rollbackFor=Exception.class)
    public MediaVideoAudit begin(CosVideoAuditPayload payload,JobExecutionContext context) {
        context.requireOwnership();
        var row=rows.lock(payload.auditId());
        if(row==null || payload.sequence()!=row.getPollSequence()) return null;
        if(!java.util.Set.of("PENDING","SUBMITTING","RUNNING").contains(row.getState())) return row;
        if("SUBMITTING".equals(row.getState())) {
            // A reclaimed submission may already have reached CI. Never submit it a second time.
            row.setState("UNKNOWN"); save(row); return row;
        }
        if(!row.getDeadlineAt().isAfter(now())) { row.setState("FAILED"); save(row); return row; }
        if("PENDING".equals(row.getState())) { row.setState("SUBMITTING"); save(row); }
        return row;
    }

    @Transactional(rollbackFor=Exception.class)
    public void unknown(CosVideoAuditPayload payload,JobExecutionContext context) {
        context.requireOwnership(); var row=rows.lock(payload.auditId());
        if(row!=null && row.getPollSequence()==payload.sequence() && "SUBMITTING".equals(row.getState())) { row.setState("UNKNOWN"); save(row); }
    }

    @Transactional(rollbackFor=Exception.class)
    public void submitted(CosVideoAuditPayload payload,String jobId,int pollSeconds,JobExecutionContext context) {
        context.requireOwnership(); var row=rows.lock(payload.auditId());
        if(row==null || !"SUBMITTING".equals(row.getState()) || row.getPollSequence()!=payload.sequence()) return;
        row.setProviderJobId(jobId); row.setState("RUNNING"); next(row,pollSeconds);
    }

    @Transactional(rollbackFor=Exception.class)
    public void observed(CosVideoAuditPayload payload,CosVideoAuditResult result,int pollSeconds,JobExecutionContext context) {
        context.requireOwnership(); var row=rows.lock(payload.auditId());
        if(row==null || !"RUNNING".equals(row.getState()) || row.getPollSequence()!=payload.sequence()) return;
        if(!Objects.equals(row.getProviderJobId(),result.jobId()) || !Objects.equals(dataId(row),result.dataId()) || !Objects.equals(row.getObjectKey(),result.objectKey())) throw new IllegalArgumentException("CI video audit identity mismatch");
        if(!row.getDeadlineAt().isAfter(now())) row.setState("FAILED");
        else if(result.decision()==CosVideoAuditResult.Decision.PENDING) { next(row,pollSeconds); return; }
        else row.setState(result.decision().name());
        save(row);
    }
    private void next(MediaVideoAudit row,int delay) {
        row.setPollSequence(row.getPollSequence()+1); save(row);
        enqueue(row,row.getPollSequence(),now().plusSeconds(delay));
    }
    private void enqueue(MediaVideoAudit row,int sequence,LocalDateTime when) {
        jobs.create(new JobDefinition(JOB_TYPE,1,ObservabilityContext.ensureOperationId(),
                "video-audit:"+row.getId()+":"+sequence,"media-video-audit",row.getId().toString(),
                new CosVideoAuditPayload(row.getId(),sequence),when,4));
    }
    public static String dataId(MediaVideoAudit row) { return "article-video-audit:"+row.getId(); }
    private void save(MediaVideoAudit row) { row.setUpdatedAt(now()); if(rows.updateById(row)!=1) throw new IllegalStateException("Video audit fact update failed"); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
}
