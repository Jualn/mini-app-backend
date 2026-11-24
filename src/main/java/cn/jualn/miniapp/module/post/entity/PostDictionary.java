package cn.jualn.miniapp.module.post.entity;

import lombok.Data;

@Data
public class PostDictionary {
    private Long id;
    private String code;
    private String name;
    private String status;
    private Integer displayOrder;
    private Long revision;
}
