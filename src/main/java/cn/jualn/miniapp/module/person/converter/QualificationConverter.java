package cn.jualn.miniapp.module.person.converter;

import cn.jualn.miniapp.module.person.bo.QualificationBO;
import cn.jualn.miniapp.module.person.vo.QualificationVO;
import java.util.function.Function;

public final class QualificationConverter {
    private QualificationConverter() {}
    public static QualificationVO.Import rosterImport(QualificationBO.Import b) {return new QualificationVO.Import(b.importId(),b.organizationId(),b.sourceReference(),b.state(),b.columns(),b.version(),b.expiresAt(),b.rowCount(),b.invalidRowCount(),b.importedCount(),mapping(b.mapping()),b.namespace(),b.fileStatus(),b.fileRetainUntil(),b.planId(),b.planExpiresAt());}
    private static QualificationVO.Mapping mapping(QualificationBO.Mapping mapping) {
        if (mapping == null) return null;
        var columns = mapping.columns();
        var wire = new QualificationVO.MappingColumns(columns.realName(), columns.studentNumber(),
                columns.majorClass() == 0 ? null : columns.majorClass(), columns.stageId());
        return new QualificationVO.Mapping(mapping.sheetIndex(), mapping.headerRow(), wire, mapping.stageValues());
    }
    public static QualificationVO.Entry entry(QualificationBO.Entry b) {return new QualificationVO.Entry(b.rosterEntryId(),b.organizationId(),b.realName(),b.studentNumberMasked(),b.majorClass(),b.expectedStageId(),b.baselineStatus(),b.sourceReference(),b.version(),b.personId(),b.accountBindingState(),b.personIdentity()==null?null:PersonConverter.identity(b.personIdentity()));}
    public static QualificationVO.PreviewPage preview(QualificationBO.PreviewPage b) {return new QualificationVO.PreviewPage(b.items(),b.page(),b.pageSize(),b.totalItems(),b.evaluatedAt(),b.importId(),b.version(),b.sheetIndex(),b.headerRow(),b.planId(),b.planExpiresAt(),b.review());}
    public static QualificationVO.WorkbookSheets sheets(QualificationBO.WorkbookSheets b) {return new QualificationVO.WorkbookSheets(b.importId(),b.version(),b.sheets());}
    public static QualificationVO.HeaderColumns columns(QualificationBO.HeaderColumns b) {return new QualificationVO.HeaderColumns(b.importId(),b.version(),b.sheetIndex(),b.headerRow(),b.columns());}
    public static QualificationVO.Application application(QualificationBO.Application b) {return new QualificationVO.Application(b.applicationId(),b.applicantUserId(),b.status(),b.submittedAt(),b.version(),b.verificationMethod(),b.decidedAt(),b.personId(),b.bindingId());}
    public static QualificationVO.Mentor mentor(QualificationBO.Mentor b) {return new QualificationVO.Mentor(b.personId(),b.realName(),b.position(),b.affiliation(),b.personStatus(),b.bindingState(),b.accountAvailability(),b.qualificationStatus(),b.qualificationId(),b.validFrom(),b.validUntil(),b.verifiedAt(),b.verifiedByUserId(),b.verificationReference(),b.version(),b.evaluatedAt());}
    public static QualificationVO.CertifiedMentor certified(QualificationBO.CertifiedMentor b) {return new QualificationVO.CertifiedMentor(b.userId(),b.realName(),b.position(),b.affiliation(),b.certified(),b.evaluatedAt(),b.validUntil(),b.personId(),b.bindingId(),b.qualificationId());}
    public static QualificationVO.Audit audit(QualificationBO.Audit b) {return new QualificationVO.Audit(b.changeId(),b.resourceId(),b.operation(),b.operatorUserId(),b.recordedAt(),b.reason(),b.verificationReference(),b.correctsChangeId(),b.verificationMethod(),b.sourceReference());}
    public static QualificationVO.Answer answer(QualificationBO.Answer b) {return new QualificationVO.Answer(b.postId(),b.commentId(),b.createdAt());}
    public static QualificationVO.CreationRecovery recovery(QualificationBO.CreationRecovery b) {return new QualificationVO.CreationRecovery(b.state(),b.importId());}
    public static QualificationVO.MyIdentity myIdentity(QualificationBO.MyIdentity b) {
        var r=b.currentRecord();var record=r==null?null:new cn.jualn.miniapp.module.person.vo.PersonVO.IdentityRecord(r.recordId(),r.identityKind(),r.stageId(),r.status(),PersonConverter.time(r.validFrom()),PersonConverter.time(r.validUntil()));
        return new QualificationVO.MyIdentity(b.userId(),b.identityVerification(),b.presence(),record,b.effectiveStatus(),b.evaluatedAt(),b.verifiedAt(),b.verificationMethod(),b.personId(),b.bindingId());
    }
    public static <T,R> QualificationVO.Page<R> page(QualificationBO.Page<T> b,Function<T,R> f) {return new QualificationVO.Page<>(b.items().stream().map(f).toList(),b.page(),b.pageSize(),b.totalItems(),b.evaluatedAt());}
}
