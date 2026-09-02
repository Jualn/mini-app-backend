package cn.jualn.miniapp.module.content.bo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminContentQueryBO {
    private String keyword;
    private String type;
    private String status;
    private String feature;
    private String sort;
    private String cursor;
    private Integer pageSize;
}
