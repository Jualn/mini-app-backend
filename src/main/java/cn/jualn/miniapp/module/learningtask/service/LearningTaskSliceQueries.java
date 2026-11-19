package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskSliceMapper;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskSliceVO;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;

@Service
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class LearningTaskSliceQueries {
    private final LearningTaskService tasks;
    private final LearningTaskProviders providers;
    private final LearningTaskSliceMapper mapper;
    private final LearningTaskLedgerMapper ledger;
    private final LearningTaskSliceConverter converter;
    private final Clock clock;
    public LearningTaskSliceQueries(LearningTaskService tasks,LearningTaskProviders providers,LearningTaskSliceMapper mapper,
            LearningTaskLedgerMapper ledger,LearningTaskSliceConverter converter,@Qualifier("learningTaskClock") Clock clock) {
        this.tasks=tasks; this.providers=providers; this.mapper=mapper; this.ledger=ledger; this.converter=converter; this.clock=clock;
    }
    public long ownTask(long assignment) {
        providers.authorize(LearningTaskBO.Action.MY_READ,null,null);
        Long task=mapper.assignmentTask(assignment);if(task==null) throw missing();tasks.mine(task,assignment);return task;
    }
    public LearningTaskSliceVO.MyPage mine(String cursor,int size,String progress) {
        long user=providers.authorize(LearningTaskBO.Action.MY_READ,null,null);
        LocalDateTime before=null; Long beforeId=null;
        if(cursor!=null) {
            try {
                if(cursor.length()>1024) { throw new IllegalArgumentException(); }
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(parts.length!=6 || !parts[0].equals("1") || Long.parseLong(parts[1])!=user
                        || !parts[2].equals(progress==null?"":progress) || Integer.parseInt(parts[3])!=size) { throw new IllegalArgumentException(); }
                before=LocalDateTime.parse(parts[4]); beforeId=Long.parseLong(parts[5]);
                if(beforeId<=0) { throw new IllegalArgumentException(); }
            } catch(IllegalArgumentException | java.time.DateTimeException failure) { throw invalidCursor(); }
        }
        var rows=mapper.mine(user,progress,before,beforeId,size+1);
        var selected=rows.stream().limit(size).toList();
        var items=selected.stream().map(a -> converter.mine(tasks.mine(a.getTaskId(),a.getId()))).toList();
        String next=null;
        if(rows.size()>size) {
            var last=selected.get(selected.size()-1);
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(("1|"+user+"|"+(progress==null?"":progress)+"|"+size+"|"+
                    last.getAssignedAt()+"|"+last.getId()).getBytes(StandardCharsets.UTF_8));
        }
        return new LearningTaskSliceVO.MyPage(items,next);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LearningTaskSliceVO.AssignmentPage assignments(long task,int page,int size,String allocation,String progress,Long user) {
        providers.authorize(LearningTaskBO.Action.ASSIGNMENT_READ,task,null);
        tasks.get(task);
        var items=mapper.page(task,allocation,progress,user,((long)page-1)*size,size).stream()
                .map(id -> converter.assignment(tasks.adminAssignment(task,id).assignment())).toList();
        return new LearningTaskSliceVO.AssignmentPage(items,page,size,mapper.count(task,allocation,progress,user));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LearningTaskSliceVO.Statistics statistics(long task) {
        providers.authorize(LearningTaskBO.Action.STATISTICS,task,null);
        var t=tasks.get(task).task();
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Shanghai"));
        int active=0,completed=0,notStarted=0,pending=0,rejected=0,overdue=0,onTime=0,historicalCompleted=0,revoked=0;
        var all=ledger.assignments(task);
        for(var a:all) {
            if("COMPLETED".equals(a.getProgress())) { historicalCompleted++; }
            if(!"ACTIVE".equals(a.getAllocationStatus())) { revoked++; continue; }
            active++;
            switch(a.getProgress()) {
                case "COMPLETED" -> completed++;
                case "NOT_STARTED" -> notStarted++;
                case "PENDING_REVIEW" -> pending++;
                case "REJECTED" -> rejected++;
                default -> throw new IllegalStateException("Unknown assignment progress");
            }
            if(Boolean.TRUE.equals(a.getOnTimeFirstSubmission())) { onTime++; }
            if(t.status()!=LearningTaskBO.Status.CANCELLED && ("NOT_STARTED".equals(a.getProgress()) && !now.isBefore(t.firstDeadline())
                    || "REJECTED".equals(a.getProgress()) && a.getSupplementDeadline()!=null && !now.isBefore(a.getSupplementDeadline()))) { overdue++; }
        }
        return new LearningTaskSliceVO.Statistics(active,completed,notStarted,pending,rejected,overdue,onTime,mapper.submissions(task,false),
                mapper.submissions(task,true),all.size(),revoked,historicalCompleted,converter.time(now),active==0?null:((double)completed/active));
    }
    private ContractProblemException invalidCursor() {
        return new ContractProblemException(org.springframework.http.HttpStatus.BAD_REQUEST,"/problems/invalid-cursor","Cursor does not match current user and query");
    }
}
