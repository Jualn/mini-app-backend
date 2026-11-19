package cn.jualn.miniapp.module.learningtask;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.*;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmitterSummaryReader;
import cn.jualn.miniapp.module.person.service.profile.SubmissionNameReader;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LearningTaskSubmitterSummaryTest {
    final LocalDateTime submitted=LocalDateTime.of(2026,10,30,10,0);
    Submission submission(Subject subject) {return new Submission(7,3,1,"text",List.of(),null,submitted,ReviewStatus.PENDING_REVIEW,null,null,null,1,99,subject,"original-binding");}
    @Test void readsFixedSubmitterAndConvertsBusinessTimeToBindingUtc() {
        var names=mock(SubmissionNameReader.class);when(names.name("original-person","original-binding",99,submitted.minusHours(8))).thenReturn("Approved Name");
        var summary=new LearningTaskSubmitterSummaryReader(names).read(submission(Subject.person("original-person")));
        assertEquals("AVAILABLE",summary.availability());assertEquals("Approved Name",summary.displayName());
        verify(names).name("original-person","original-binding",99,submitted.minusHours(8));
    }
    @Test void accountDoesNotAcquireIdentityFromItsCurrentBindingOrNickname() {
        var names=mock(SubmissionNameReader.class);var summary=new LearningTaskSubmitterSummaryReader(names).read(submission(Subject.account(99)));
        assertEquals("UNAVAILABLE",summary.availability());assertNull(summary.displayName());verifyNoInteractions(names);
    }
    @Test void failedLocalNameSourceDoesNotBlockAuthorizedSubmissionRead() {
        var mapper=mock(ManagedPersonMapper.class);when(mapper.binding("original-binding")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("source down"));
        var summary=new LearningTaskSubmitterSummaryReader(new SubmissionNameReader(mapper)).read(submission(Subject.person("original-person")));
        assertEquals("UNAVAILABLE",summary.availability());assertNull(summary.displayName());
    }
}
