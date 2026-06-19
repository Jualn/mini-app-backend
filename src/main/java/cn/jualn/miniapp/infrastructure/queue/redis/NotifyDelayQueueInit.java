package cn.jualn.miniapp.infrastructure.queue.redis;

import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 服务启动时，把 DB 中未发送的延迟任务同步到 Redis ZSET。
 *
 * <p>解决 Redis 重启或数据丢失导致任务漏发的问题。
 * DB {@code notify_queue} 表是持久化来源，ZSET 是触发器。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDelayQueueInit implements ApplicationRunner {
    private final NotifyPlanMapper notifyPlanMapper;
    private final NotifyService notifyService;

    @Override
    public void run(ApplicationArguments args) {
        // 查 status=0（待发送）的任务，重新入队列
        List<NotifyPlan> pending = notifyPlanMapper.selectPending();
        if (pending.isEmpty()) {
            log.info("[DelayQueue] 启动恢复：无待发任务");
            return;
        }

        int recovered = 0;
        for (NotifyPlan task : pending) {
            // 统一走 service 入延迟队列链路，避免初始化与运行期行为不一致。
            notifyService.enqueueNotifyPlan(task.getId());
            recovered++;
        }

        log.info("[DelayQueue] 启动恢复完成，恢复 {}/{} 条任务", recovered, pending.size());
    }
}
