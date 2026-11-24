package cn.jualn.miniapp.module.report.bo;
import java.time.OffsetDateTime;
import java.util.List;
public record ReportResolutionBO(Long id,Long operatorId,OffsetDateTime resolvedAt,String decision,
        String contentAction,String outcome,String remark,List<String> reportIds) { }
