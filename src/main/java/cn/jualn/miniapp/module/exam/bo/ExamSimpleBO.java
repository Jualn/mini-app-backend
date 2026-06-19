package cn.jualn.miniapp.module.exam.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamSimpleBO {

    private Long id;

    private String title;

    private LocalDate examDate;
}
