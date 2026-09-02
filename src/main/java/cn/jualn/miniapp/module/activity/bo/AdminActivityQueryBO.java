package cn.jualn.miniapp.module.activity.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminActivityQueryBO {

    private String keyword;
    private Integer status;
    private Integer category;
    private Integer audienceMask;
    private String sort;
    private String cursor;
    private Integer pageSize;
}
