package cn.jualn.miniapp.module.person.service.profile;

import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Minimal internal disclosure boundary, called only after the owning submission review authorization. */
@Service @RequiredArgsConstructor
public class SubmissionNameReader {
    private final ManagedPersonMapper persons;
    public String name(String personId,String bindingId,long submittedBy,LocalDateTime submittedAtUtc) {
        if(personId==null || bindingId==null || submittedAtUtc==null) return null;
        try {
            var binding=persons.binding(bindingId);
            if(binding==null || binding.getUserId()!=submittedBy || !personId.equals(binding.getPersonId())
                || binding.getStartedAt()==null || binding.getStartedAt().isAfter(submittedAtUtc)
                || binding.getEndedAt()!=null && !submittedAtUtc.isBefore(binding.getEndedAt())) return null;
            var profile=persons.profile(personId);
            return profile==null || !"ACTIVE".equals(profile.getStatus()) ? null : profile.getRealName();
        } catch(DataAccessException failure) { return null; }
    }
}
