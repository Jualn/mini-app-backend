package cn.jualn.miniapp.module.publicevent.vo;

import lombok.Data;

import java.time.LocalDate;

@Data
public class PublicEventSimpleVO {
    private Long id;

    private String title;

    private LocalDate examDate;
}
