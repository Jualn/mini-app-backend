package cn.jualn.miniapp.third.ai.task;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI任务存储组件。用于保存AI生成的文本结果，并提供一个任务ID供前端查询。
 * 文本会在5分钟后自动过期删除，或者在被取回后立即删除，保证文本只会被取回一次。
 * 这种设计适用于AI生成的文本结果只需要被前端查询一次的场景，比如AI解析上传的文件生成文本，前端需要查询这个文本来展示或者继续后续的AI交互，但不需要长期保存这个文本结果。
 * 这种设计的好处是简单易用，不需要引入外部的缓存系统（比如Redis）来存储这些临时数据，直接在内存中使用ConcurrentHashMap来存储，并利用Java的定时任务功能来实现自动过期删除，满足了大多数AI生成文本结果的使用场景。
 * 需要注意的是，这种设计适用于单实例部署的应用，如果应用需要水平扩展到多实例部署，那么这种基于内存的存储方式就不适用了，需要引入分布式缓存系统来存储这些临时数据，以保证不同实例之间的数据一致性和可用性。
 */
@Component
public class AiTaskStore {

    private final ConcurrentHashMap<String, String> pending =
            new ConcurrentHashMap<>();

    /**
     * 保存文本，返回一个任务ID。5分钟内可以通过任务ID取回文本，5分钟后文本会被自动删除。
     * @param text 文本内容
     * @return 任务ID
     */
    public String save(String text) {

        String taskId = UUID.randomUUID().toString();

        pending.put(taskId, text);

        CompletableFuture.delayedExecutor(
                5,
                TimeUnit.MINUTES
        ).execute(() -> pending.remove(taskId));

        return taskId;
    }

    public String get(String taskId) {
        return pending.get(taskId);
    }

    public String remove(String taskId) {
        return pending.remove(taskId);
    }
}
