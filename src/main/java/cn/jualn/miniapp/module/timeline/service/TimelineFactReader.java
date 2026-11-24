package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Live owner facts for reminder checks; no existence cache or HTTP-oriented lookup. */
@Service
@RequiredArgsConstructor
public class TimelineFactReader {
    public record Start(Integer precision,LocalDateTime time) { }
    private final TimelineMapper timelines;
    public Start start(long timelineId,TargetType type,long subjectId) {
        var node = timelines.selectById(timelineId);
        if (node == null || !Objects.equals(node.getTargetType(),type.getCode())
                || !Objects.equals(node.getTargetId(),subjectId)) return null;
        return new Start(node.getStartPrecision(),node.getStartTime());
    }
}
