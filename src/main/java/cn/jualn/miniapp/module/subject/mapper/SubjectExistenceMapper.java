package cn.jualn.miniapp.module.subject.mapper;

import java.util.List;
import java.util.Set;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Explicit read-only compatibility projection. Existence never grants access or write eligibility.
 */
@Mapper
public interface SubjectExistenceMapper {
    boolean exists(@Param("type") int type, @Param("id") long id);

    Set<Long> existingIds(@Param("type") int type, @Param("ids") List<Long> ids);
}
