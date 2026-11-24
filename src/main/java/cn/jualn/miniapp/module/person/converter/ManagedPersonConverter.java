package cn.jualn.miniapp.module.person.converter;

import cn.jualn.miniapp.module.person.bo.ManagedPersonBO;
import cn.jualn.miniapp.module.person.vo.ManagedPersonVO;
import cn.jualn.miniapp.module.person.service.binding.ManagedPersonBindingService;
import java.util.LinkedHashMap;
import java.util.Map;
import static cn.jualn.miniapp.module.person.converter.PersonConverter.time;

public final class ManagedPersonConverter {
    private ManagedPersonConverter() {}
    public static ManagedPersonVO.Profile profile(ManagedPersonBO.Profile p) { return new ManagedPersonVO.Profile(p.personId(), p.realName(), p.status(), p.version(), time(p.createdAt()), time(p.updatedAt()), p.lastChangeId()); }
    public static ManagedPersonVO.Registration registration(ManagedPersonBO.Registration r) {
        var p = r.provenance(); return new ManagedPersonVO.Registration(r.personId(), r.majorClass(), r.lastAction(), p == null ? null : new ManagedPersonVO.Provenance(p.sourceKind(), p.sourceReference(), p.confirmedByUserId(), time(p.confirmedAt())), r.version());
    }
    public static ManagedPersonVO.Binding binding(ManagedPersonBO.Binding b) { return new ManagedPersonVO.Binding(b.bindingId(), b.userId(), b.personId(), b.status(), b.version(), time(b.startedAt()), b.verificationMethod(), b.sourceReference(), b.startedByUserId(), time(b.endedAt()), b.endedByUserId(), b.endReason()); }
    public static ManagedPersonVO.BindingChange bindingChange(ManagedPersonBO.BindingChange b) { return new ManagedPersonVO.BindingChange(b.endedBindingIds(), binding(b.binding())); }
    public static ManagedPersonVO.PersonSlot slot(ManagedPersonBO.PersonSlot p) { return new ManagedPersonVO.PersonSlot(p.state(), p.personId(), p.binding() == null ? null : binding(p.binding()), p.version()); }
    public static ManagedPersonVO.MyBinding mine(ManagedPersonBO.MyBinding b) { return new ManagedPersonVO.MyBinding(b.state(), b.userId(), b.personId(), b.bindingId(), b.personStatus(), b.version(), time(b.evaluatedAt())); }
    public static ManagedPersonVO.AuditPage history(ManagedPersonBO.AuditPage p) { return new ManagedPersonVO.AuditPage(p.items().stream().map(a -> new ManagedPersonVO.Audit(a.changeId(), a.personId(), a.operation(), a.operatorUserId(), time(a.recordedAt()), a.reason(), a.verificationReference(), a.aggregateKind(), a.resourceId(), a.beforeVersion(), a.afterVersion())).toList(), p.page(), p.pageSize(), p.totalItems(), time(p.evaluatedAt())); }
    public static ManagedPersonVO.BindingPage bindings(ManagedPersonBindingService.BindingPage p) { return new ManagedPersonVO.BindingPage(p.items().stream().map(ManagedPersonConverter::binding).toList(), p.page(), p.pageSize(), p.totalItems(), time(p.evaluatedAt())); }
    public static Map<String, Object> recovery(Map<String, Object> value) {
        var result = new LinkedHashMap<>(value); var body = result.get("result");
        if (body instanceof ManagedPersonBO.Profile p) result.put("result", profile(p));
        else if (body instanceof ManagedPersonBO.Binding b) result.put("result", binding(b));
        else if (body instanceof ManagedPersonBO.BindingChange c) result.put("result", bindingChange(c));
        return result;
    }
}
