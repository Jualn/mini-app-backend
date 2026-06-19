package cn.jualn.miniapp.module.exam.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamListBO {
    private Long id;

    private String title;

    private Integer category;

    private LocalDate examDate;
}
