package cn.jualn.miniapp.module.user.mapper;

import cn.jualn.miniapp.common.web.NameRow;

import cn.jualn.miniapp.module.user.bo.UserPublicProfileBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Collection;


public interface UserProfileMapper extends BaseMapper<UserProfile> {
    @Select("SELECT id,role,status,ban_reason,ban_expire_at,last_login_at FROM user_profile WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    UserAuthRow lockContentAccount(long id);
    @Select("SELECT session_version FROM user_profile WHERE id=#{userId} AND deleted_at IS NULL")
    Long selectSessionVersion(@Param("userId") long userId);

    @Update("UPDATE user_profile SET session_version=session_version+1 WHERE id=#{userId} AND deleted_at IS NULL")
    int invalidateSessions(@Param("userId") long userId);

    List<NameRow> selectAdminNames(@Param("ids") java.util.Collection<Long> ids);
    @Select({"<script>","SELECT id FROM user_profile WHERE status=1 AND deleted_at IS NULL AND openid IS NOT NULL AND openid != '' AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY id FOR SHARE","</script>"})
    List<Long> selectLearningTaskEligibleForShare(@Param("ids") List<Long> ids);

    @Select("SELECT id FROM user_profile WHERE status=1 AND deleted_at IS NULL AND openid IS NOT NULL AND openid != '' ORDER BY id")
    List<Long> selectLearningTaskEligibleDirectory();

    UserProfile selectProfileForUpdate(@Param("userId") Long userId);

    UserAuthRow selectCurrentLoginForShare(@Param("userId") long userId);

    java.time.LocalDateTime selectCurrentUtcTime();

    int updateCheckedProfile(@Param("userId") Long userId, @Param("revision") long revision,
                             @Param("profile") UserProfile profile);

    @Select("SELECT openid FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    String selectMiniOpenid(Long id);

    @Select("SELECT mp_openid FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    String selectMpOpenIdById(Long id);

    @Select({"<script>SELECT id FROM user_profile WHERE deleted_at IS NULL AND openid IS NOT NULL AND TRIM(openid) != '' AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>"})
    List<Long> selectMiniProgramUserIds(@Param("ids") Collection<Long> ids);

    List<Long> selectOfficialAccountUserIds(@Param("ids") Collection<Long> ids);

    @Select("SELECT id FROM user_profile WHERE mp_openid = #{mpOpenid} AND deleted_at IS NULL ORDER BY id LIMIT 2")
    List<Long> selectIdsByMpOpenid(String mpOpenid);

    @Update("UPDATE user_profile SET mp_openid = #{mpOpenid} " +
            "WHERE id = #{userId} AND deleted_at IS NULL AND (mp_openid IS NULL OR mp_openid = #{mpOpenid})")
    int bindMpOpenidIfUnchanged(@Param("userId") Long userId, @Param("mpOpenid") String mpOpenid);

    @Select("SELECT status FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    Integer selectStatusById(Long id);

    @Select("SELECT id, nickname, avatar_url, (role IN (2, 3)) AS platform_operator " +
            "FROM user_profile WHERE id = #{userId} AND deleted_at IS NULL")
    UserSimpleBO selectUserSimpleBOById(Long userId);

    List<UserSimpleBO> selectSimpleBatch(List<Long> ids);

    @Select("SELECT id, nickname, avatar_url, background_url, bio, gender,created_at " +
            "FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    UserPublicProfileBO selectPublicProfileBOById(Long id);

    @Select("SELECT id, role, status, ban_reason, ban_expire_at, last_login_at " +
            "FROM user_profile WHERE id = #{userId} AND deleted_at IS NULL")
    UserAuthRow selectUserAuthRowByUserId(Long userId);

    List<AdminUserListRow> selectAdminUserPage(
            @Param("status") Integer status,
            @Param("deactivated") Boolean deactivated,
            @Param("role") Integer role,
            @Param("keyword") String keyword,
            @Param("keywordId") Long keywordId,
            @Param("sort") String sort,
            @Param("lastId") Long lastId,
            @Param("limit") Integer limit);

    AdminUserSummaryRow selectAdminUserSummary();

    AdminUserDetailRow selectAdminUserDetail(@Param("userId") Long userId);

}
