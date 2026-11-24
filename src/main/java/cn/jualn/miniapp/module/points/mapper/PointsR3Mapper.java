package cn.jualn.miniapp.module.points.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy.Plan;
import cn.jualn.miniapp.module.points.bo.PointsBO.Query;
import cn.jualn.miniapp.module.points.bo.PointsBO.ParticipantTotals;
import cn.jualn.miniapp.module.points.bo.PointsBO.OrganizationTotals;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptanceTotals;
import cn.jualn.miniapp.module.points.entity.PointApplication;
import cn.jualn.miniapp.module.points.entity.PointLedger;
import cn.jualn.miniapp.module.points.bo.PointsBO.ManagementOrganization;
@Mapper public interface PointsR3Mapper {
    List<PointLedger> managementLedgers(@Param("p") Plan plan,@Param("q") Query query);
    long managementLedgerCount(@Param("p") Plan plan,@Param("q") Query query);
    List<ManagementOrganization> managementOrganizations(@Param("p") Plan plan,@Param("view") String view,@Param("search") String search,@Param("q") Query query);
    long managementOrganizationCount(@Param("p") Plan plan,@Param("view") String view,@Param("search") String search);
    long currentOrganizationCount(@Param("p") cn.jualn.miniapp.module.points.service.PointsAcceptancePolicy.Plan plan);
    long unresolvedApplications(@Param("p") Plan plan,@Param("q") Query query);
    List<String> pendingOrganizations(@Param("p") Plan plan,@Param("q") Query query);
    List<PointApplication> applications(@Param("p") Plan plan,@Param("q") Query query);
    long applicationCount(@Param("p") Plan plan,@Param("q") Query query);
    long unknownParticipants(@Param("p") Plan plan);
    long participantCount(@Param("p") Plan plan);
    List<ParticipantTotals> personStatistics(@Param("p") Plan plan,@Param("q") Query query);
    List<OrganizationTotals> currentStatistics(@Param("p") Plan plan,@Param("q") Query query);
    long unresolvedLedger();
    List<AcceptanceTotals> acceptanceStatistics(@Param("p") Plan plan,@Param("q") Query query);
}
