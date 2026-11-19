package cn.jualn.miniapp.module.person.service.support;
import cn.jualn.miniapp.module.person.bo.QualificationBO.*;
import cn.jualn.miniapp.module.person.mapper.QualificationMapper;
import java.time.LocalDateTime;
import java.util.List;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.*;
/** Internal read projections shared by roster and mentor resources. */
public final class PersonnelReads {
    private PersonnelReads() {}
    public static <T> Page<T> paginate(List<T> all, int page, int size, LocalDateTime at) {
        long start = (long) (page - 1) * size;
        return new Page<>(start >= all.size() ? List.of() : List.copyOf(all.subList((int) start, (int) Math.min(start + size, all.size()))), page, size, all.size(), instant(at));
    }

    public static <T> Resource<T> resource(T value, long revision, int status, String location) {
        return new Resource<>(value, etag(revision), location, status);
    }

    public static Page<Audit> historyPage(QualificationMapper mapper, String type, String id, int page, int size, LocalDateTime at) {
        return new Page<>(mapper.history(type, id, size, (long) (page - 1) * size).stream().map(e -> new Audit(e.getId(), e.getAggregateId(), e.getOperation(), Long.toString(e.getOperatorUserId()), instant(e.getRecordedAt()),
                e.getReason(), e.getVerificationReference(), e.getCorrectsEventId(), e.getVerificationMethod(), e.getSourceReference())).toList(), page, size, mapper.historyCount(type, id), instant(at));
    }

}
