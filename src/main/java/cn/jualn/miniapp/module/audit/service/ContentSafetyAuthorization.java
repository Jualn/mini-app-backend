package cn.jualn.miniapp.module.audit.service;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service @RequiredArgsConstructor
public class ContentSafetyAuthorization {
    private final CurrentAuthorization authorization;
    public static String duty(String space) {
        return switch(space) {
            case "POST" -> "POST_SAFETY_REVIEW";
            case "ACTIVITY" -> "ACTIVITY_SAFETY_REVIEW";
            case "PUBLIC_EVENT" -> "PUBLIC_EVENT_COMMENT_REVIEW";
            default -> throw new BusinessException(ResultCode.FORBIDDEN);
        };
    }
    public void checkCurrent(long actor,String space,boolean decide) {var d=authorization.current(actor);if(!d.allowsWork(duty(space),space,decide?new String[]{"review:read","review:decide"}:new String[]{"review:read"}))throw new BusinessException(ResultCode.FORBIDDEN);}
    public void require(long actor,String space,boolean decide,boolean legacy) {
        if(legacy && authorization.current(actor).source()==RbacModels.Source.LEGACY) {
            authorization.requireLegacyOnly(actor,decide?"review:decide":"review:read");return;
        }
        authorization.requireWork(actor,duty(space),space,decide?new String[]{"review:read","review:decide"}:new String[]{"review:read"});
    }
    public List<String> readable(long actor) {
        var d=authorization.current(actor);
        if(d.source()==RbacModels.Source.LEGACY) {
            authorization.requireLegacyOnly(actor,"review:read");return null;
        }
        var spaces=List.of("POST","ACTIVITY","PUBLIC_EVENT").stream().filter(s->d.allowsWork(duty(s),s,"review:read")).toList();
        if(spaces.isEmpty()) throw new BusinessException(ResultCode.FORBIDDEN);
        return spaces;
    }
}
