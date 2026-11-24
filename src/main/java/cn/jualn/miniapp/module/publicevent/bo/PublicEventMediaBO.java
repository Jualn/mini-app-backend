package cn.jualn.miniapp.module.publicevent.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 考试附件业务对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicEventMediaBO {

    private Integer type;
    private String url;
    private String originalName;
    private Integer sortOrder;
}

