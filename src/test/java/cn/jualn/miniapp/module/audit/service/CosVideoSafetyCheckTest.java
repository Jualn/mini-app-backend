package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.entity.MediaVideoAudit;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.third.cos.config.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosVideoSafetyCheckTest {
    final CosVideoAuditWriter writer=mock(CosVideoAuditWriter.class);
    final CosVideoAuditProperties policy=new CosVideoAuditProperties();
    final CosProperties cos=new CosProperties("id","secret","bucket-1250000000","ap-guangzhou","https://cdn.example",1800,"cdn.example");
    final CosVideoSafetyCheckService safety=new CosVideoSafetyCheckService(writer,cos,policy);
    MediaAttachmentBO video() { return MediaAttachmentBO.builder().id(1L).objectKey("article/retained/9/video").kind("VIDEO").verified(true).sizeBytes(100L).durationSeconds(30d).build(); }

    @Test void disabledModerationAllowsVerifiedVideoWithoutEnqueuingAndEnabledNeedsPolicy() {
        safety.reserve(1,"article/retained/9/video",100,9);
        assertDoesNotThrow(()->safety.requirePassed(video(),9));
        var basic=video(); basic.setDurationSeconds(null);
        assertDoesNotThrow(()->safety.requirePassed(basic,9));
        var invalid=video();invalid.setVerified(false);
        assertThrows(ContractProblemException.class,()->safety.requirePassed(invalid,9));
        verifyNoInteractions(writer); policy.setEnabled(true);
        assertThrows(ContractProblemException.class,()->safety.reserve(1,"article/retained/9/video",100,9));
    }
    @Test void publicationUsesOnlyExactPolicyPassAndChangingPolicyInvalidatesPriorApproval() {
        policy.setEnabled(true); policy.setBizType("strategy");
        var basic=video(); basic.setDurationSeconds(null);
        assertThrows(ContractProblemException.class,()->safety.requirePassed(basic,9));
        when(writer.reserve(any())).thenAnswer(call->{var row=(MediaVideoAudit)call.getArgument(0); row.setState("PASS"); return row;});
        assertDoesNotThrow(()->safety.requirePassed(video(),9));
        String initial=safety.policyKey(); policy.setPolicyRevision("2"); assertNotEquals(initial,safety.policyKey());
        initial=safety.policyKey(); policy.setSnapshotIntervalSeconds(2); assertNotEquals(initial,safety.policyKey());
        for(String state:new String[]{"PENDING","SUBMITTING","RUNNING","UNKNOWN","FAILED","REVIEW"}) {
            var row=new MediaVideoAudit(); row.setState(state); doReturn(row).when(writer).reserve(any());
            assertEquals(503,assertThrows(ContractProblemException.class,()->safety.requirePassed(video(),9)).getStatus().value());
        }
        var rejected=new MediaVideoAudit(); rejected.setState("REJECT"); doReturn(rejected).when(writer).reserve(any());
        assertEquals(409,assertThrows(ContractProblemException.class,()->safety.requirePassed(video(),9)).getStatus().value());
        var changed=video(); changed.setVerified(false); assertThrows(ContractProblemException.class,()->safety.requirePassed(changed,9));
        changed.setVerified(true); changed.setDurationSeconds(20001d); assertThrows(ContractProblemException.class,()->safety.requirePassed(changed,9));
    }
}
