package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.exception.ExternalServiceException;
import cn.jualn.miniapp.module.learningtask.LearningTaskProblems;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskTextSafety;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.wx.client.WxClient;
import cn.jualn.miniapp.third.wx.dto.WxMsgSecCheckRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LearningTaskTextSafetyCheckService implements LearningTaskTextSafety {
    private static final Logger log = LoggerFactory.getLogger(LearningTaskTextSafetyCheckService.class);
    private final WxClient wx;
    private final UserWechatIdentityService users;
    private final boolean allowEmptyKeyword;
    public LearningTaskTextSafetyCheckService(WxClient wx, UserWechatIdentityService users) {
        this(wx, users, new org.springframework.core.env.StandardEnvironment());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LearningTaskTextSafetyCheckService(WxClient wx, UserWechatIdentityService users, org.springframework.core.env.Environment environment) {
        this.wx=wx; this.users=users;
        this.allowEmptyKeyword=environment.acceptsProfiles(
                org.springframework.core.env.Profiles.of("dev & learning-task-integration & !prod"));
    }

    @Override @Transactional(propagation=Propagation.NEVER)
    public void check(long actor, String text) {
        if(text==null || text.isEmpty()) { return; }
        String diagnostic = "PERSON_IDENTITY";
        String providerCode = "unknown";
        boolean accepted = false, rejected = false;
        try {
            String openid=users.getMiniOpenid(actor);
            if(openid==null || openid.isBlank()) { throw unavailable(); }
            for(int offset=0;offset<text.length();) {
                int end=text.offsetByCodePoints(offset,Math.min(2000,text.codePointCount(offset,text.length())));
                // Provider's documented scene 1 = material/profile; no Article capability is implied.
                diagnostic = "PROVIDER_CALL";
                var response=wx.msgSecCheck(WxMsgSecCheckRequest.builder().openid(openid)
                        .content(text.substring(offset,end)).version(2).scene(1).build());
                providerCode = response == null || response.getErrcode() == null ? "unknown" : response.getErrcode().toString();
                diagnostic = "PROVIDER_RESPONSE";
                if(response==null || !Integer.valueOf(0).equals(response.getErrcode())) { throw unavailable(); }
                String result=response.getResultSuggest();
                boolean verifiedPass = response.isVerifiedPassStructure()
                        || allowEmptyKeyword && response.isVerifiedEmptyKeywordPassStructure();
                if (!verifiedPass && response.getDetail() != null) {
                    for (var item : response.getDetail()) {
                        if (item == null) { diagnostic = "DETAIL_NULL"; throw unavailable(); }
                        if (!Integer.valueOf(0).equals(item.getErrcode())) {
                            diagnostic = item.getErrcode() == null ? "DETAIL_CODE_MISSING" : "DETAIL_PROVIDER_ERROR";
                            providerCode = item.getErrcode() == null ? "unknown" : item.getErrcode().toString();
                            throw unavailable();
                        }
                        if ("pass".equals(result) && !"pass".equals(item.getSuggest())) {
                            diagnostic = "DETAIL_NOT_PASS"; throw unavailable();
                        }
                    }
                }
                if("risky".equals(result) || "reject".equals(result)) {
                    rejected = true;
                    throw ContractProblemException.validation(new ContractProblemException.Violation(
                            "body","#/","CONTENT_REJECTED","LearningTask text did not pass content safety"));
                }
                diagnostic = "review".equals(result) ? "RESULT_REVIEW" : "RESULT_UNKNOWN";
                if(!"pass".equals(result)) { throw unavailable(); }
                if (!verifiedPass) { diagnostic="RESULT_STRUCTURE_UNVERIFIED"; throw unavailable(); }
                offset=end;
            }
            accepted = true;
        } catch(ExternalServiceException | WebClientException | BusinessException failure) {
            if (failure instanceof ExternalServiceException remote) {
                String code = remote.getProviderCode();
                providerCode = code != null && code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "unknown";
            }
            var problem=unavailable(); problem.initCause(failure); throw problem;
        } finally {
            if (!accepted && !rejected) {
                log.warn("result=failure errorCategory=remote provider=wechat code={} providerCode={} errorCode={}",
                        cn.jualn.miniapp.common.result.ResultCode.WX_API_ERROR.getCode(), providerCode, diagnostic);
            }
        }
    }
    private ContractProblemException unavailable() {
        return LearningTaskProblems.unavailable(LearningTaskProblems.Dependency.CONTENT_SAFETY);
    }
}
