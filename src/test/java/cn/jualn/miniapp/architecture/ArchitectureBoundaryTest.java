package cn.jualn.miniapp.architecture;

import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks imports and fully qualified Java references; ignores comments and SQL/string literals. */
class ArchitectureBoundaryTest {
    private static ModuleBoundaryModel model;
    private static List<BoundaryExceptions.Entry> exceptions;

    @org.junit.jupiter.api.BeforeAll static void loadBoundaries() throws Exception {
        model=ModuleBoundaryModel.load();
        exceptions=BoundaryExceptions.load();
    }

    @Test void discoveredPublicOperationsUseBusinessSignatures() {
        BoundaryExceptions.verify("signature",model.signatureViolations(),exceptions);
    }

    @Test void allModulesRespectResponsibilityDependencies() {
        BoundaryExceptions.verify("responsibility",model.responsibilityViolations(),exceptions);
    }

    @Test void crossModuleTypesHaveNoDirectOrIndirectDependencyCycles() {
        var graph=model.typeGraph();
        var cycles=ModuleBoundaryModel.cycles(graph).stream()
                .filter(c->c.stream().map(ModuleBoundaryModel::module).distinct().count()>1).toList();
        assertTrue(cycles.isEmpty(),()->"Cross-module type cycles: "+cycles);
    }

    @Test void discoveryIncludesEveryCurrentBusinessModule() throws Exception {
        var expected=new java.util.TreeSet<String>();
        try(var paths=Files.list(Path.of("src/main/java/cn/jualn/miniapp/module"))) {
            paths.filter(Files::isDirectory).map(p->p.getFileName().toString()).forEach(expected::add);
        }
        var actual=new java.util.TreeSet<String>();model.classes.keySet().stream().map(ModuleBoundaryModel::module).forEach(actual::add);
        assertEquals(expected,actual,"A module must never silently disappear from the guard");
        var capabilityCounts=new java.util.TreeMap<String,Long>();
        model.calls.keySet().stream().filter(model::capability).forEach(name->capabilityCounts.merge(ModuleBoundaryModel.module(name),1L,Long::sum));
        var report=new java.util.LinkedHashMap<String,Object>();
        report.put("modules",actual);report.put("productionTypes",model.classes.size());
        report.put("activeCapabilityTypesByModule",capabilityCounts);
        report.put("signatureExceptions",exceptions.stream().filter(e->e.rule().equals("signature")).toList());
        report.put("responsibilityExceptions",exceptions.stream().filter(e->e.rule().equals("responsibility")).toList());
        report.put("crossModuleTypeCycles",ModuleBoundaryModel.cycles(model.typeGraph()).stream()
                .filter(c->c.stream().map(ModuleBoundaryModel::module).distinct().count()>1).toList());
        var out=Path.of("target/boundary-guards");Files.createDirectories(out);
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("inventory.json").toFile(),report);
    }

    @Test void publicCapabilitiesUseBusinessTypes() {
        var owners=List.of(cn.jualn.miniapp.module.post.service.PostQueries.class,
                cn.jualn.miniapp.module.comment.service.CommentQueries.class,
                cn.jualn.miniapp.module.post.service.PostGovernanceService.class,
                cn.jualn.miniapp.module.comment.service.CommentGovernanceService.class,
                cn.jualn.miniapp.module.user.service.UserService.class,
                cn.jualn.miniapp.module.user.service.UserAccountService.class,
                cn.jualn.miniapp.module.user.service.UserWechatIdentityService.class,
                cn.jualn.miniapp.module.user.service.UserAgreementService.class,
                cn.jualn.miniapp.module.activity.service.ActivityQueries.class,
                cn.jualn.miniapp.module.activity.service.ActivityAdminQueries.class,
                cn.jualn.miniapp.module.activity.service.ActivityDraftService.class,
                cn.jualn.miniapp.module.activity.service.ActivityLifecycleService.class,
                cn.jualn.miniapp.module.activity.service.ActivityReviewService.class,
                cn.jualn.miniapp.module.publicevent.service.PublicEventQueries.class,
                cn.jualn.miniapp.module.publicevent.service.PublicEventAdminQueries.class,
                cn.jualn.miniapp.module.publicevent.service.PublicEventDraftService.class,
                cn.jualn.miniapp.module.publicevent.service.PublicEventLifecycleService.class,
                cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService.class,
                cn.jualn.miniapp.module.publicevent.service.HomePublicMatterReminderService.class,
                cn.jualn.miniapp.module.interact.service.InteractionLikes.class,
                cn.jualn.miniapp.module.interact.service.InteractionLikeQueries.class,
                cn.jualn.miniapp.module.interact.service.InteractionCounts.class,
                cn.jualn.miniapp.module.interact.service.InteractionShares.class,
                cn.jualn.miniapp.module.interact.service.InteractionViews.class);
        for(var owner:owners) for(var method:owner.getDeclaredMethods()) {
            if(!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            var types=new ArrayList<java.lang.reflect.Type>();
            types.add(method.getGenericReturnType());
            types.addAll(List.of(method.getGenericParameterTypes()));
            for(var type:types) for(var internal:List.of(".dto.request.",".vo.",".entity.",".mapper.",".controller."))
                assertFalse(type.getTypeName().contains(internal),()->method+" exposes "+type);
        }
    }

    @Test void foundationAndPersistenceBoundariesRemainExplicit() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"Architecture checks require the project's JDK 17");
        var root = Path.of("src/main/java");
        List<Path> files;
        try (var paths = Files.walk(root)) {files = paths.filter(p -> p.toString().endsWith(".java")).toList();}
        var failures = new ArrayList<String>();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8)) {
            var task = (JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,
                    manager.getJavaFileObjectsFromPaths(files));
            for (var unit : task.parse()) {
                String own = unit.getPackageName().toString();
                new TreeScanner<Void,Void>() {
                    void check(String reference) {
                        String base = "cn.jualn.miniapp.";
                        boolean foundation = own.startsWith(base+"common.")
                                || own.startsWith(base+"infrastructure.") || own.startsWith(base+"third.");
                        if (foundation && reference.startsWith(base+"module."))
                            failures.add(own+" -> "+reference);
                        if(own.startsWith(base+"module.notify.") && (reference.startsWith(base+"module.wx.")
                                || reference.startsWith(base+"third.wx.")))
                            failures.add(own+" -> provider-owned notification dependency "+reference);
                        if (own.startsWith(base+"module.") && reference.startsWith(base+"module.")) {
                            String from = own.substring((base+"module.").length()).split("\\.")[0];
                            String to = reference.substring((base+"module.").length()).split("\\.")[0];
                            if (own.equals(base+"module.authorization.mapper")
                                    && reference.startsWith(base+"module.authorization.service."))
                                failures.add(own+" -> Authorization service-owned persistence projection "+reference);
                            if (from.equals("authorization") && !own.contains(".web")
                                    && reference.startsWith(base+"module.authorization.web."))
                                failures.add(own+" -> Authorization HTTP representation "+reference);
                            if (!from.equals("authorization") && reference.startsWith(base+"module.authorization.web.")
                                    && !reference.equals(base+"module.authorization.web.RbacHttpAccess")
                                    && !reference.startsWith(base+"module.authorization.web.RbacHttpAccess."))
                                failures.add(own+" -> Authorization HTTP internals "+reference);
                            if (!from.equals("authorization") && (reference.startsWith(base+"module.authorization.support.RbacProblems")
                                    || reference.startsWith(base+"module.authorization.support.UnifiedProblems")))
                                failures.add(own+" -> Authorization error internals "+reference);
                            if(from.equals("person") && reference.equals(base+"module.authorization.mapper.RbacMapper"))
                                failures.add(own+" -> Authorization persistence "+reference);
                            if(!from.equals("person") && (reference.startsWith(base+"module.person.service.support.")
                                    || reference.equals(base+"module.person.service.binding.PersonBindingTransitions")))
                                failures.add(own+" -> Person internal collaboration "+reference);
                            if (!from.equals(to) && (reference.contains(".mapper.") || reference.contains(".entity.")))
                                failures.add(own+" -> "+reference);
                            if (!from.equals(to) && to.equals("interact") && reference.contains(".service.impl."))
                                failures.add(own+" -> Interact internal collaboration "+reference);
                            if (!from.equals(to) && (to.equals("activity") || to.equals("publicevent"))
                                    && (reference.contains(".service.impl.")
                                    || reference.equals(base+"module."+to+".service."+(to.equals("activity")?"ActivityService":"PublicEventService"))))
                                failures.add(own+" -> broad/internal event capability "+reference);
                            if (!from.equals(to) && (to.equals("post") || to.equals("comment"))
                                    && (reference.contains(".service.impl.")
                                    || reference.equals(base+"module."+to+".service."+(to.equals("post")?"PostService":"CommentService"))))
                                failures.add(own+" -> broad author/internal capability "+reference);
                        }
                    }
                    @Override public Void visitImport(ImportTree node,Void unused) {
                        check(node.getQualifiedIdentifier().toString());return super.visitImport(node,unused);
                    }
                    @Override public Void visitMemberSelect(MemberSelectTree node,Void unused) {
                        check(node.toString());return super.visitMemberSelect(node,unused);
                    }
                }.scan(unit,null);
            }
        }
        assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind()==Diagnostic.Kind.ERROR),
                () -> diagnostics.getDiagnostics().toString());
        assertTrue(failures.isEmpty(),() -> String.join("\n",failures.stream().distinct().toList()));
    }
}
