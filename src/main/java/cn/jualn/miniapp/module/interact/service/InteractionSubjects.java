package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.common.enums.TargetType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Resolves explicit subject capabilities without depending on their broad business services. */
@Service
public class InteractionSubjects {
    private final Map<TargetType,InteractionSubject> subjects = new EnumMap<>(TargetType.class);
    public InteractionSubjects(List<InteractionSubject> implementations) {
        for (var implementation : implementations)
            if (subjects.putIfAbsent(implementation.targetType(),implementation) != null)
                throw new IllegalStateException("Duplicate interaction subject: " + implementation.targetType());
    }
    public InteractionSubject require(TargetType type) {
        var subject = subjects.get(type);
        if (subject == null) throw new IllegalArgumentException("Unsupported interaction subject: " + type);
        return subject;
    }
}
