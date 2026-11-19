package cn.jualn.miniapp.module.publicevent.mapper;

import lombok.Data;

/** PublicEvent-owned persisted view-count projection. */
@Data
public class PublicEventViewCountRow {
    private Long targetId;
    private Integer count;
}
