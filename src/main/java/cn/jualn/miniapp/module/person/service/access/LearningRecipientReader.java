package cn.jualn.miniapp.module.person.service.access;
import cn.jualn.miniapp.module.person.mapper.LearningRecipientMapper;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** Minimal internal directory projection, only after the LearningTask owner proves its audience ceiling. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class LearningRecipientReader {
    public record Item(String id,String personId,String name,String disambiguation) {
        public Item(String id,String personId,String name) {this(id,personId,name,null);}
    }
    private final LearningRecipientMapper mapper;
    /** The caller has already authorized disclosure of these exact identifiers. */
    public List<Item> selectedNames(boolean account, List<String> ids) {
        if (ids.size() > 50) throw new IllegalArgumentException("Bounded LearningTask selection required");
        if (ids.isEmpty()) return List.of();
        return mapper.selectedNames(account, ids).stream()
                .map(row -> new Item(row.id(), row.personId(), row.name(), row.disambiguation())).toList();
    }

    public List<Item> organizations(boolean platform,List<String> ids,String q,String after,int limit) {
        if(limit<1 || limit>51) throw new IllegalArgumentException("Bounded LearningTask organization page required");
        return mapper.organizations(platform,ids,q,after,limit).stream().map(r->new Item(r.id(),null,r.name(),null)).toList();
    }
    public List<Item> candidates(boolean account,List<Target> ceilings,List<String> organizations,List<String> stages,String q,String after,int limit,LocalDateTime at) {
        if(limit<1 || limit>51) throw new IllegalArgumentException("Bounded LearningTask candidate page required");
        return mapper.candidates(account,ceilings,organizations,stages,q,after,limit,at).stream().map(r->new Item(r.id(),r.personId(),r.name(),r.disambiguation())).toList();
    }
}
