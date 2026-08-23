package cn.jualn.miniapp.module.admin.auth.vo;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminIdentityVO {
    private String id;
    private String displayName;
    private String avatarText;
    private String roleCode;
    private String roleLabel;
    private List<String> permissions;
}
