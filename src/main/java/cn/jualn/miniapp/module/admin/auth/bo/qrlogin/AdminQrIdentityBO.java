package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record AdminQrIdentityBO(String id, String displayName, String avatarText, String roleCode,
        String roleLabel, List<String> permissions,String authorizationModel) {
    public AdminQrIdentityBO(String id,String displayName,String avatarText,String roleCode,String roleLabel,List<String> permissions) {
        this(id,displayName,avatarText,roleCode,roleLabel,permissions,null);
    }
    public AdminQrIdentityBO { permissions = permissions==null?null:List.copyOf(permissions); }
}
