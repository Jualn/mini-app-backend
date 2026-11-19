package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.user.converter.UserConverter;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.event.UserCreatedEvent;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/** 微信身份查询、首次注册与服务号绑定的状态所有者。 */
@Service
@RequiredArgsConstructor
public class UserWechatIdentityService {
    private final UserProfileMapper userProfileMapper;
    private final UserConverter userConverter;
    private final ApplicationEventPublisher eventPublisher;
    private final cn.jualn.miniapp.module.authorization.service.AuthorizationAuthorityService authorities;

    /**
     * 根据微信小程序 openid 获取用户基本信息。
     *
     * @param openid 微信小程序openid
     * @return 用户基本信息，用于拼接给登录返回
     */
    @Transactional(rollbackFor = Exception.class)
    public UserInfoDTO getUserInfo(String openid) {
        if (!StringUtils.hasText(openid)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "openid 不能为空");
        }

        UserProfile userProfile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>()
                        .select(UserProfile::getId, UserProfile::getNickname,
                                UserProfile::getAvatarUrl, UserProfile::getRole)
                        .eq(UserProfile::getOpenid, openid)
        );

        if (userProfile == null) {
            // 首次登录自动注册。
            String defaultUserName = "用户" + UUID.randomUUID().toString().substring(0, 6);
            userProfile = UserProfile.builder()
                    .openid(openid)
                    .nickname(defaultUserName)
                    .role(UserRole.USER.getCode())  // 只会回填 id，这里设置避免下方转化拿到null
                    .build();
            userProfileMapper.insert(userProfile);
            authorities.initializeNewUser(userProfile.getId());
            eventPublisher.publishEvent(new UserCreatedEvent(userProfile.getId()));
        }

        return userConverter.toUserInfoDTO(userProfile);
    }

    /**
     * 获取用户的微信小程序 openid。
     */
    public String getMiniOpenid(Long userId) {
        String openid = userProfileMapper.selectMiniOpenid(userId);
        if (openid == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return openid;
    }

    /** 未绑定时返回 null。 */
    public String getOfficialAccountOpenid(Long userId) {
        if (userId == null) {
            return null;
        }
        return userProfileMapper.selectMpOpenIdById(userId);
    }

    /** 未绑定时返回 null；历史关联有歧义时拒绝选择任意账号。 */
    public Long findUserIdByOfficialAccountOpenid(String mpOpenid) {
        if (!StringUtils.hasText(mpOpenid)) {
            return null;
        }
        List<Long> userIds = userProfileMapper.selectIdsByMpOpenid(mpOpenid);
        if (userIds.size() > 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "服务号身份存在重复关联，暂不能继续");
        }
        return userIds.isEmpty() ? null : userIds.get(0);
    }

    /** 同一身份重复绑定幂等；禁止替换已有身份或争用其他用户身份。 */
    @Transactional(rollbackFor = Exception.class)
    public void bindOfficialAccountIdentity(Long userId, String mpOpenid) {
        if (userId == null || !StringUtils.hasText(mpOpenid)) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "服务号身份关联参数不完整");
        }

        String current = userProfileMapper.selectMpOpenIdById(userId);
        if (mpOpenid.equals(current)) {
            return;
        }
        if (StringUtils.hasText(current)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "当前用户已绑定其他服务号身份");
        }

        Long existingOwner = findUserIdByOfficialAccountOpenid(mpOpenid);
        if (existingOwner != null && !existingOwner.equals(userId)) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "该服务号身份已关联其他用户");
        }

        try {
            if (userProfileMapper.bindMpOpenidIfUnchanged(userId, mpOpenid) != 1) {
                throw new BusinessException(ResultCode.DATA_CONFLICT, "用户身份已变化，请重新发起绑定");
            }
        } catch (DuplicateKeyException conflict) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "该服务号身份已关联其他用户");
        }
    }
}
