package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.*;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PostCommunityNotifications {
    private final NotifyService notify;
    @Transactional(propagation=Propagation.MANDATORY)
    public void handled(long actor,long id,long revision,String action,boolean comment) {
        String title="社区内容处理结果";
        Target target=new Target(comment?"MY_POST_COMMENT_DETAIL":"MY_POST_DETAIL",comment?null:Long.toString(id),comment?Long.toString(id):null,null,null);
        notify.processNotificationPayload(NotifyPayload.builder().receiverId(actor).type(NotifyType.POST_CONTENT_HANDLED)
                .title(title).content("内容状态已更新，请查看本人内容详情。")
                .sourceKey("community:"+(comment?"comment:":"post:")+id+":"+revision+":"+action)
                .snapshot(new Snapshot(new Presentation(title,"内容状态已更新，请查看本人内容详情。",null,null,null),null,
                        new Subject(comment?"COMMENT":"POST",Long.toString(id)),target)).build());
    }
}
