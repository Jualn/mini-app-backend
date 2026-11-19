package cn.jualn.miniapp.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryGuardRulesTest {
    @TempDir Path temporary;

    @org.springframework.web.bind.annotation.RestController
    public static class RootHttpEntry { }
    @org.springframework.stereotype.Service
    public static class RootCapability { }

    @Test void annotationsIdentifyRolesOutsideConventionalPackages() {
        var model=new ModuleBoundaryModel();
        String http="cn.jualn.miniapp.module.fixture.Entry",service="cn.jualn.miniapp.module.fixture.UseCase";
        model.classes.put(http,RootHttpEntry.class);model.classes.put(service,RootCapability.class);
        assertTrue(model.http(http));assertTrue(model.capability(service));assertFalse(model.capability(http));
        assertFalse(model.http("cn.jualn.miniapp.common.web.NameReadBO"));
        assertFalse(model.http("org.springframework.web.context.WebApplicationContext"));
    }

    @Test void signaturesIncludeInheritedMethodsNestedGenericsBoundsArraysAndExceptions() throws Exception {
        String base="cn.jualn.miniapp.module.guardfixture.";
        var sources=Map.of(
            base+"entity.Row","public class Row {}",
            base+"dto.request.Failure","public class Failure extends RuntimeException {}",
            base+"service.Parent","public interface Parent<T extends "+base+"entity.Row> { T[] inherited(); }",
            base+"service.LooseParent","public interface LooseParent<T> { java.util.List<T[]> inheritedUnbounded(); }",
            base+"service.Api","public interface Api<T extends "+base+"entity.Row> extends Parent<T>, LooseParent<"+base+"entity.Row> { <X extends "+base+"entity.Row> java.util.List<? super X[]> nested(java.util.Map<String,? extends T[]> values) throws "+base+"dto.request.Failure; }");
        var files=new ArrayList<String>();
        for(var entry:sources.entrySet()) {
            var path=temporary.resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(path.getParent());
            Files.writeString(path,"package "+entry.getKey().substring(0,entry.getKey().lastIndexOf('.'))+"; "+entry.getValue());files.add(path.toString());
        }
        var compiler=ToolProvider.getSystemJavaCompiler();
        var args=new ArrayList<>(List.of("-proc:none","-d",temporary.toString()));args.addAll(files);
        assertEquals(0,compiler.run(null,null,null,args.toArray(String[]::new)));
        try(var loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()},getClass().getClassLoader())) {
            var model=new ModuleBoundaryModel();String api=base+"service.Api";
            model.classes.put(api,Class.forName(api,false,loader));model.calls.put(api,Set.of());
            var failures=model.signatureViolations();
            assertEquals(4,failures.size());
            assertTrue(failures.stream().anyMatch(s->s.contains("#inherited") && s.endsWith("entity.Row")));
            assertTrue(failures.stream().anyMatch(s->s.contains("#inheritedUnbounded") && s.endsWith("entity.Row")));
            assertTrue(failures.stream().anyMatch(s->s.contains("#nested") && s.endsWith("entity.Row")));
            assertTrue(failures.stream().anyMatch(s->s.endsWith("dto.request.Failure")));
        }
    }

    @Test void bytecodeFindsFullyQualifiedCallsClassLiteralsAndMethodReferencesButNotStrings() {
        var writer=new ClassWriter(0);
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"cn/jualn/miniapp/module/alpha/service/Port",null,"java/lang/Object",null);
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"run","()V",null,null);
        method.visitCode();
        method.visitLdcInsn("cn.jualn.miniapp.module.beta.entity.StringOnly");method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(Type.getObjectType("cn/jualn/miniapp/module/beta/entity/Literal"));method.visitInsn(Opcodes.POP);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,"cn/jualn/miniapp/module/beta/service/impl/Hidden","go","()V",false);
        method.visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC,"cn/jualn/miniapp/module/beta/service/impl/Reference","go","()V",false));method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);method.visitMaxs(1,0);method.visitEnd();writer.visitEnd();
        var refs=new TreeSet<String>();var calls=new TreeSet<String>();
        ModuleBoundaryModel.readReferences(writer.toByteArray(),refs,(owner,m)->calls.add(owner+"#"+m));
        assertFalse(refs.stream().anyMatch(s->s.endsWith("StringOnly")));
        assertTrue(refs.contains("cn.jualn.miniapp.module.beta.entity.Literal"));
        assertEquals(Set.of("cn.jualn.miniapp.module.beta.service.impl.Hidden#go()V","cn.jualn.miniapp.module.beta.service.impl.Reference#go()V"),calls);
        var model=new ModuleBoundaryModel();model.dependencies.put("cn.jualn.miniapp.module.alpha.service.Port",refs);
        assertEquals(3,model.responsibilityViolations().size());
    }

    @Test void cycleDetectionFindsDirectAndIndirectCyclesWithoutRejectingADiamond() {
        assertEquals(List.of(Set.of("a","b")),ModuleBoundaryModel.cycles(Map.of("a",Set.of("b"),"b",Set.of("a"))));
        assertEquals(List.of(Set.of("a","b","c")),ModuleBoundaryModel.cycles(Map.of("a",Set.of("b"),"b",Set.of("c"),"c",Set.of("a"))));
        assertTrue(ModuleBoundaryModel.cycles(Map.of("a",Set.of("b","c"),"b",Set.of("d"),"c",Set.of("d"),"d",Set.of())).isEmpty());
        assertEquals(List.of(Set.of("a")),ModuleBoundaryModel.cycles(Map.of("a",Set.of("a"))));
    }

    @Test void exceptionsRejectNewViolationsStaleEntriesWildcardsMissingReasonsAndDuplicates() {
        var row="DEBT\tsignature\towner#method -> type\tKnown transport leak";
        var entries=BoundaryExceptions.parse(List.of(row));
        assertDoesNotThrow(()->BoundaryExceptions.verify("signature",List.of("owner#method -> type"),entries));
        assertThrows(AssertionError.class,()->BoundaryExceptions.verify("signature",List.of("owner#method -> type","new leak"),entries));
        assertThrows(AssertionError.class,()->BoundaryExceptions.verify("signature",List.of(),entries));
        assertThrows(IllegalArgumentException.class,()->BoundaryExceptions.parse(List.of(row,row)));
        assertThrows(IllegalArgumentException.class,()->BoundaryExceptions.parse(List.of(row.replace("type","*"))));
        assertThrows(IllegalArgumentException.class,()->BoundaryExceptions.parse(List.of("DEBT\tsignature\tkey\t")));
    }
}
