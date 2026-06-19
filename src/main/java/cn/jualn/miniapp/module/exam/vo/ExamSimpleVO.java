package cn.jualn.miniapp.module.exam.vo;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ExamSimpleVO {
    private Long id;

    private String title;

    private LocalDate examDate;
}
