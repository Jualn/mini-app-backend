package cn.jualn.miniapp.module.user.mapper;

import cn.jualn.miniapp.module.user.bo.UserPublicProfileBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;


public interface UserProfileMapper extends BaseMapper<UserProfile> {

    @Select("SELECT openid FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    String selectMiniOpenid(Long id);

    @Select("SELECT mp_openid FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    String selectMpOpenIdById(Long id);

    @Select("SELECT status FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    Integer selectStatusById(Long id);

    @Select("SELECT id, nickname, avatar_url " +
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
