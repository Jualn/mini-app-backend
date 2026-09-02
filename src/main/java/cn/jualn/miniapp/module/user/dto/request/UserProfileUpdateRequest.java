package cn.jualn.miniapp.module.user.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户资料更新请求。
 */
@Data
public class UserProfileUpdateRequest {

    @Size(max = 10, message = "昵称长度不能超过10")
    private String nickname;

    @Size(max = 512, message = "头像地址长度不能超过512")
    private String avatarUrl;

    @Size(max = 512, message = "头像objectKey长度不能超过512")
    private String avatarObjectKey;

    @Size(max = 512, message = "背景图地址长度不能超过512")
    private String backgroundUrl;

    @Size(max = 512, message = "背景图objectKey长度不能超过512")
    private String backgroundObjectKey;

    @Size(max = 200, message = "简介长度不能超过200")
    private String bio;

    @Min(value = 0, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;
}

