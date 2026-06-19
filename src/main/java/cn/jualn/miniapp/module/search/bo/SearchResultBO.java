package cn.jualn.miniapp.module.search.bo;

import cn.jualn.miniapp.common.enums.TargetType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SearchResultBO {

    private TargetType targetType;

    private Long targetId;

    private String title;

    private String summary;

    private Integer likeCount;

    private Integer commentCount;

    private Integer viewCount;

    private LocalDateTime publishedAt;
}

