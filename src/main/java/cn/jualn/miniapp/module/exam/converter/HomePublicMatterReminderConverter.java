package cn.jualn.miniapp.module.exam.converter;

import cn.jualn.miniapp.module.exam.bo.HomePublicMatterRemindersBO;
import cn.jualn.miniapp.module.exam.vo.HomePublicMatterRemindersVO;
import org.springframework.stereotype.Component;

@Component
public class HomePublicMatterReminderConverter {

    public HomePublicMatterRemindersVO toVO(HomePublicMatterRemindersBO source) {
        return new HomePublicMatterRemindersVO(
                source.evaluatedAt(),
                source.source().name(),
                source.items().stream()
                        .map(item -> new HomePublicMatterRemindersVO.PublicMatterReminderVO(
                                item.publicMatterId().toString(), item.name(), item.nodeName(), item.reminderAt()))
                        .toList());
    }
}
