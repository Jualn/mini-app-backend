package cn.jualn.miniapp.module.learningtask.service;
import cn.jualn.miniapp.module.notify.service.NotificationDeliveryEligibility;
import cn.jualn.miniapp.module.notify.bo.NotificationDeliveryBO;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import java.util.Objects;

/** Business-owned revalidation at each external send; never grants access from a frozen identity. */
@Service @RequiredArgsConstructor
public class LearningTaskNotificationEligibility implements NotificationDeliveryEligibility {
    private final PersonBusinessScopes persons;
    private final UserCurrentLoginAccess accounts;
    @Transactional
    public boolean isEligible(NotificationDeliveryBO notification,Map<String,Object> frozen){
        if(NotificationCategory.of(NotifyType.fromCode(notification.type()))!=NotificationCategory.LEARNING_TASK)return true;
        String person=frozen.get("recipientPersonId")==null?null:frozen.get("recipientPersonId").toString();
        String binding=frozen.get("recipientBindingId")==null?null:frozen.get("recipientBindingId").toString();
        if(person!=null){
            persons.lock(false);
            var current=persons.deliveryBinding(person);
            if(current!=null && "VERIFICATION_REQUIRED".equals(current.status()))throw new IllegalStateException("Recipient binding unverified");
            if(current==null || !"ACTIVE".equals(current.status()) || !Objects.equals(current.userId(),notification.userId())
                    || binding==null || !binding.equals(current.bindingId()))return false;
        }
        try {accounts.require(notification.userId());return true;}
        catch(BusinessException unavailable){
            if(unavailable.getResultCode()==ResultCode.USER_BANNED||unavailable.getResultCode()==ResultCode.USER_NOT_FOUND)return false;
            throw unavailable;
        }
    }
}
