package cn.jualn.miniapp.module.activity.mapper;

import cn.jualn.miniapp.module.activity.entity.ActivityRegistration;
import cn.jualn.miniapp.module.activity.bo.ActivityRegistrationBO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Collection;

@Mapper
public interface ActivityRegistrationMapper extends BaseMapper<ActivityRegistration> {
    List<ActivityRegistrationBO> page(@Param("activityId") Long activityId, @Param("status") Integer status,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);
    List<ActivityRegistration> exportRows(@Param("activityId") Long activityId,
            @Param("status") Integer status, @Param("limit") int limit);
    List<ActivityRegistration> pageContract(@Param("activityId") Long activityId, @Param("status") Integer status,
            @Param("offset") long offset, @Param("limit") int limit);
    long countContract(@Param("activityId") Long activityId, @Param("status") Integer status);
    List<cn.jualn.miniapp.module.activity.bo.ActivityRegistrationCountBO> countSubmittedByActivityIds(
            @Param("activityIds") Collection<Long> activityIds);
    List<ActivityRegistration> exportContract(@Param("activityId") Long activityId,
            @Param("status") Integer status);
    int restore(@Param("id") Long id, @Param("data") String data, @Param("now") java.time.LocalDateTime now);
    int restoreVersioned(@Param("id") Long id, @Param("version") long version, @Param("data") String data,
            @Param("formVersion") String formVersion, @Param("now") java.time.LocalDateTime now);
    int replaceVersioned(@Param("id") Long id, @Param("version") long version, @Param("data") String data,
            @Param("now") java.time.LocalDateTime now);
    int cancelVersioned(@Param("id") Long id, @Param("version") long version,
            @Param("now") java.time.LocalDateTime now);
    int cancel(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
    int invalidate(@Param("id") Long id, @Param("operatorId") Long operatorId,
            @Param("reason") String reason, @Param("now") java.time.LocalDateTime now);
}
