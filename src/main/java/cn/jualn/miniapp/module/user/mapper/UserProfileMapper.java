package cn.jualn.miniapp.module.user.mapper;

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

    UserProfile selectProfileForUpdate(@Param("userId") Long userId);

    int updateCheckedProfile(@Param("userId") Long userId, @Param("revision") long revision,
                             @Param("profile") UserProfile profile);

    @Select("SELECT openid FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    String selectMiniOpenid(Long id);

    @Select("SELECT mp_openid FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    String selectMpOpenIdById(Long id);

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

    @Select("SELECT id FROM user_profile " +
            "WHERE role = 3 AND deleted_at IS NULL ORDER BY id FOR UPDATE")
    List<Long> selectAdminIdsForUpdate();
}
