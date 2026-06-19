package cn.jualn.miniapp.infrastructure.queue.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueMessage <T extends MessagePayload> {
    private String topic;
    private String traceId;
    private Long userId;

    private int retryCount;

    /** 有效载荷，即在数据传输过程中，所携带或传输的实际有效信息或数据内容 就像请求中的 Request body*/
    private T payload;
}
