package cn.jualn.miniapp.module.audit.service;
import cn.jualn.miniapp.module.audit.mapper.ContentSafetyReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
/** Author-facing state only, without management evidence or provider identifiers. */
@Service @RequiredArgsConstructor
public class CommunitySafetyStates {
    private final ContentSafetyReviewMapper reviews;
    public String current(int type,long id,Long revision,String fallback) {
        var r=reviews.latest(type,id);
        if(r==null || revision!=null && !revision.equals(r.getBusinessRevision()))return fallback;
        return switch(r.getState()) {case "check-failed"->"CHECK_FAILED";case "pending"->"REVIEW_REQUIRED";case "rejected"->"REJECTED";case "approved"->"PASSED";default->fallback;};
    }
}
