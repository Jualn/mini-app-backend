package cn.jualn.miniapp.module.points.mapper;

import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;

import cn.jualn.miniapp.module.points.bo.PointsBO.AuthorizationAudit;
import cn.jualn.miniapp.module.points.bo.PointsBO.OrganizationTotals;
import cn.jualn.miniapp.module.points.bo.PointsBO.ParticipantTotals;
import cn.jualn.miniapp.module.points.bo.PointsBO.Qualification;
import cn.jualn.miniapp.module.points.bo.PointsBO.Query;
import cn.jualn.miniapp.module.points.bo.PointsBO.Receipt;
import cn.jualn.miniapp.module.points.bo.PointsBO.RequestScope;
import cn.jualn.miniapp.module.points.bo.PointsBO.Totals;

import cn.jualn.miniapp.module.points.entity.PointApplication;
import cn.jualn.miniapp.module.points.entity.PointLedger;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Append-only ledger: deliberately no update/delete API. */
@Mapper
public interface PointsMapper {
    void notificationResolution(@Param("occurrence") String occurrence,@Param("outcome") String outcome,@Param("now") LocalDateTime now);
    void ensureSubject(String personId);
    String lockSubject(String personId);
    PointApplication application(long id);
    PointApplication lockApplication(long id);
    PointLedger ledger(long id);
    Long reversal(long originalId);
    List<PointLedger> correctionLedgers(String correctionId);
    int insertApplication(PointApplication row);
    int finishApplication(PointApplication row);
    int closeApplication(PointApplication row);
    Qualification qualification(String organization);
    int insertLedger(PointLedger row);
    Totals totals(String personId);
    Totals lockedTotals(String personId);
    List<PointApplication> applications(@Param("personId") String personId,@Param("q") Query query);
    long applicationCount(@Param("personId") String personId,@Param("q") Query query);
    List<PointLedger> ledgers(@Param("personId") String personId,@Param("q") Query query);
    long ledgerCount(@Param("personId") String personId,@Param("q") Query query);
    List<PointApplication> adminApplications(@Param("p") cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery plan,@Param("q") Query query);
    long adminApplicationCount(@Param("p") cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery plan,@Param("q") Query query);
    List<ParticipantTotals> personStatistics(@Param("p") cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery plan,@Param("q") Query query);
    long participantCount(@Param("p") cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery plan);
    List<OrganizationTotals> organizationStatistics(@Param("p") cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery plan,@Param("q") Query query);
    long organizationCount(@Param("p") cn.jualn.miniapp.module.person.service.facts.PersonFactReaders.PointsQuery plan);
    Receipt receipt(RequestScope scope);
    int audit(AuthorizationAudit audit);
    int saveReceipt(@Param("s") RequestScope scope,@Param("fingerprint") String fingerprint,
            @Param("json") String json,@Param("now") LocalDateTime now);
}
