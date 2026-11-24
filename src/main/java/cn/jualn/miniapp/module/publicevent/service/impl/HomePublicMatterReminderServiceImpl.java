package cn.jualn.miniapp.module.publicevent.service.impl;

import cn.jualn.miniapp.module.publicevent.bo.HomePublicMatterReminderRow;
import cn.jualn.miniapp.module.publicevent.bo.HomePublicMatterRemindersBO;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.service.HomePublicMatterReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HomePublicMatterReminderServiceImpl implements HomePublicMatterReminderService {
    static final int MAX_ITEMS = 5;
    static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final PublicEventMapper examInfoMapper;
    private final Clock applicationClock;

    @Override
    @Transactional(readOnly = true)
    public HomePublicMatterRemindersBO listForUser(long userId) {
        Instant evaluatedInstant = applicationClock.instant().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime evaluatedAt = OffsetDateTime.ofInstant(evaluatedInstant, DISPLAY_ZONE);
        LocalDateTime databaseEvaluatedAt = evaluatedAt.toLocalDateTime();

        List<HomePublicMatterReminderRow> rows = examInfoMapper.selectHomePublicMatterReminders(
                userId, databaseEvaluatedAt, MAX_ITEMS);
        HomePublicMatterRemindersBO.Source source = rows.isEmpty()
                ? HomePublicMatterRemindersBO.Source.DEFAULT
                : HomePublicMatterRemindersBO.Source.valueOf(rows.get(0).getSource());

        List<HomePublicMatterRemindersBO.PublicMatterReminderBO> items = rows.stream()
                .map(row -> new HomePublicMatterRemindersBO.PublicMatterReminderBO(
                        row.getPublicMatterId(),
                        row.getName(),
                        row.getNodeName(),
                        row.getReminderAt().atZone(DISPLAY_ZONE).toOffsetDateTime()))
                .toList();
        return new HomePublicMatterRemindersBO(evaluatedAt, source, items);
    }
}
