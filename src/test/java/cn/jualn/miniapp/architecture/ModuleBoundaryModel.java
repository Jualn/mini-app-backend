package cn.jualn.miniapp.architecture;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.asm.*;

/** Compiled Java type references; class loading never initializes application classes. */
final class ModuleBoundaryModel {
    static final String BASE = "cn.jualn.miniapp.module.";
    final SortedMap<String, Set<String>> dependencies = new TreeMap<>();
    final Map<String, Set<String>> calls = new TreeMap<>();
    final SortedMap<String, Class<?>> classes = new TreeMap<>();

    static ModuleBoundaryModel load() throws Exception {
        var model = new ModuleBoundaryModel();
        Path output = Path.of(cn.jualn.miniapp.MiniAppApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Only current production sources: incremental compilation can leave moved/deleted class files behind.
        var sources = new HashSet<String>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(
                    Path.of("src/main/java").relativize(p).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.java$", "")));
        }
        try (var files = Files.walk(output.resolve(BASE.replace('.', '/')))) {
            for (var file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String name = output.relativize(file).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                if (module(name).isEmpty()) continue;
                byte[] bytes=Files.readAllBytes(file);
                var sourceFile=new String[1];
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public void visitSource(String source,String debug) { sourceFile[0]=source; }
                },ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                if(sourceFile[0]==null) throw new IllegalStateException("Missing production SourceFile metadata: "+file);
                String source=name.substring(0,name.lastIndexOf('.')+1)+sourceFile[0].replaceAll("\\.java$", "");
                if (!sources.contains(source)) continue;
                Class<?> cls = Class.forName(name, false, ModuleBoundaryModel.class.getClassLoader());
                model.classes.put(name, cls);
                var refs = new TreeSet<String>();
                readReferences(bytes, refs, (owner, method) -> {
                    if (!method.startsWith("<") && !module(owner).isEmpty() && (!module(name).equals(module(owner)) || model.http(name)))
                        model.calls.computeIfAbsent(owner, key -> new TreeSet<>()).add(method);
                });
                refs.remove(name);
                var generic = new ArrayList<java.lang.reflect.Type>();
                if(cls.getGenericSuperclass()!=null)generic.add(cls.getGenericSuperclass());
                generic.addAll(List.of(cls.getGenericInterfaces()));
                generic.addAll(List.of(cls.getTypeParameters()));
                for(var field:cls.getDeclaredFields())generic.add(field.getGenericType());
                for(var method:cls.getDeclaredMethods()) {
                    generic.add(method.getGenericReturnType());generic.addAll(List.of(method.getGenericParameterTypes()));
                    generic.addAll(List.of(method.getGenericExceptionTypes()));generic.addAll(List.of(method.getTypeParameters()));
                }
                for(var constructor:cls.getDeclaredConstructors()) {
                    generic.addAll(List.of(constructor.getGenericParameterTypes()));
                    generic.addAll(List.of(constructor.getGenericExceptionTypes()));
                    generic.addAll(List.of(constructor.getTypeParameters()));
                }
                for(var type:generic)exposedTypes(type,refs,new HashSet<>());
                model.dependencies.put(name, refs);
                model.classes.put(name, cls);
            }
        }
        return model;
    }

    static void readReferences(byte[] bytes, Set<String> refs, java.util.function.BiConsumer<String,String> calls) {
        new ClassReader(bytes).accept(new References(refs,calls),ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    static String topLevel(String name) { return name.split("\\$", 2)[0]; }
    static String module(String name) {
        String relative=name.startsWith(BASE) ? name.substring(BASE.length()) : "";
        return relative.contains(".") ? relative.split("\\.")[0] : "";
    }
    static boolean internal(String name) {
        return name.contains(".impl.") || name.contains(".internal.") || name.contains(".support.")
                || name.contains(".bootstrap.") || name.contains(".mapper.") || name.contains(".entity.")
                || name.contains(".controller.") || name.contains(".web.");
    }
    static boolean representation(String name) {
        return name.startsWith(BASE) && ((name.contains(".dto.") && !name.contains(".dto.inner.")) || name.contains(".vo.")
                || name.contains(".controller.") || name.contains(".web.") || name.contains(".entity.") || name.contains(".mapper."));
    }

    static boolean isHttp(String name) {
        return name.contains(".controller.") || name.contains(".web.");
    }
    boolean http(String name) {
        if (!name.startsWith(BASE)) return false;
        Class<?> cls=classes.get(name);
        return isHttp(name) || cls!=null && (cls.isAnnotationPresent(org.springframework.stereotype.Controller.class)
                || cls.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class));
    }
    boolean capability(String name) {
        Class<?> cls=classes.get(name);
        return cls!=null && Modifier.isPublic(cls.getModifiers()) && !internal(name) && !http(name) && !representation(name)
                && !name.contains(".converter.") && !name.contains(".bo.") && !name.contains(".model.")
                && !name.contains(".enums.") && !name.contains(".dto.inner.") && !name.contains(".payload.data.")
                && (calls.containsKey(name) || name.contains(".service.") || cls.isAnnotationPresent(org.springframework.stereotype.Service.class));
    }
    List<String> signatureViolations() {
        var failures = new TreeSet<String>();
        for (var entry : classes.entrySet()) {
            Class<?> owner = entry.getValue();
            if (!capability(entry.getKey()) || !calls.containsKey(entry.getKey())) continue;
            for (Method method : owner.getMethods()) {
                if (method.getDeclaringClass() == Object.class || method.isBridge() || method.isSynthetic()) continue;
                if (!owner.isInterface() && !calls.get(entry.getKey()).contains(method.getName()+org.springframework.asm.Type.getMethodDescriptor(method))) continue;
                var types = new ArrayList<java.lang.reflect.Type>();
                types.add(method.getGenericReturnType());
                types.addAll(List.of(method.getGenericParameterTypes()));
                types.addAll(List.of(method.getGenericExceptionTypes()));
                types.addAll(List.of(method.getTypeParameters()));
                var exposed = new TreeSet<String>();
                for (var type : types) exposedTypes(type, exposed, new HashSet<>());
                resolvedTypes(org.springframework.core.ResolvableType.forMethodReturnType(method,owner),exposed,new HashSet<>());
                for(int i=0;i<method.getParameterCount();i++)
                    resolvedTypes(org.springframework.core.ResolvableType.forMethodParameter(method,i,owner),exposed,new HashSet<>());
                for (String type : exposed) if (representation(type) || http(type) || type.startsWith(BASE) && internal(type)) failures.add(
                        owner.getName()+"#"+method.getName()+org.springframework.asm.Type.getMethodDescriptor(method)+" -> "+type);
            }
        }
        return List.copyOf(failures);
    }

    static void exposedTypes(java.lang.reflect.Type type, Set<String> result, Set<java.lang.reflect.Type> visited) {
        if (!visited.add(type)) return;
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) exposedTypes(cls.getComponentType(), result, visited);
            else result.add(cls.getName());
        } else if (type instanceof ParameterizedType value) {
            exposedTypes(value.getRawType(), result, visited);
            if (value.getOwnerType() != null) exposedTypes(value.getOwnerType(), result, visited);
            for (var argument : value.getActualTypeArguments()) exposedTypes(argument, result, visited);
        } else if (type instanceof GenericArrayType value) exposedTypes(value.getGenericComponentType(), result, visited);
        else if (type instanceof TypeVariable<?> value) for (var bound : value.getBounds()) exposedTypes(bound, result, visited);
        else if (type instanceof WildcardType value) {
            for (var bound : value.getLowerBounds()) exposedTypes(bound, result, visited);
            for (var bound : value.getUpperBounds()) exposedTypes(bound, result, visited);
        }
    }

    private static void resolvedTypes(org.springframework.core.ResolvableType type,Set<String> result,Set<String> visited) {
        Class<?> resolved=type.resolve();
        if(!visited.add(type.getType().getTypeName()+" -> "+resolved))return;
        if(type.isArray())resolvedTypes(type.getComponentType(),result,visited);
        else if(resolved!=null)result.add(resolved.getName());
        for(var argument:type.getGenerics())resolvedTypes(argument,result,visited);
    }

    List<String> responsibilityViolations() {
        var failures = new TreeSet<String>();
        dependencies.forEach((from, refs) -> refs.forEach(to -> {
            if (!module(to).isEmpty() && !module(from).equals(module(to)) && (internal(to) || http(to))) failures.add(from+" -> "+to);
            if ((from.contains(".service.") || classes.containsKey(from) && classes.get(from).isAnnotationPresent(org.springframework.stereotype.Service.class)) && to.startsWith(BASE) && ((to.contains(".dto.") && !to.contains(".dto.inner.")) || to.contains(".vo.") || http(to))) failures.add(from+" -> business service uses boundary representation "+to);
        }));
        return List.copyOf(failures);
    }

    Map<String, Set<String>> typeGraph() {
        Map<String, Set<String>> graph = new TreeMap<>();
        dependencies.forEach((from, refs) -> {
            var edges = graph.computeIfAbsent(topLevel(from), key -> new TreeSet<>());
            refs.stream().filter(classes::containsKey).map(ModuleBoundaryModel::topLevel)
                    .filter(to -> !topLevel(from).equals(to)).forEach(edges::add);
        });
        return graph;
    }

    static List<Set<String>> cycles(Map<String, Set<String>> graph) {
        var index = new HashMap<String,Integer>(); var low = new HashMap<String,Integer>();
        var stack = new ArrayDeque<String>(); var stacked = new HashSet<String>();
        var result = new ArrayList<Set<String>>();
        for (String vertex : graph.keySet()) if (!index.containsKey(vertex)) visit(vertex, graph, index, low, stack, stacked, result);
        return result;
    }
    private static void visit(String v, Map<String,Set<String>> graph, Map<String,Integer> index,
                              Map<String,Integer> low, Deque<String> stack, Set<String> stacked, List<Set<String>> result) {
        index.put(v,index.size()); low.put(v,index.get(v)); stack.push(v); stacked.add(v);
        for (String next : graph.getOrDefault(v, Set.of())) {
            if (!index.containsKey(next)) {
                visit(next,graph,index,low,stack,stacked,result); low.put(v,Math.min(low.get(v),low.get(next)));
            } else if (stacked.contains(next)) low.put(v,Math.min(low.get(v),index.get(next)));
        }
        if (low.get(v).equals(index.get(v))) {
            var component = new TreeSet<String>(); String next;
            do { next=stack.pop();stacked.remove(next);component.add(next); } while (!next.equals(v));
            if (component.size()>1 || graph.getOrDefault(v,Set.of()).contains(v)) result.add(component);
        }
    }

    private static final class References extends ClassVisitor {
        private final Set<String> refs;
        private final java.util.function.BiConsumer<String,String> calls;
        References(Set<String> refs, java.util.function.BiConsumer<String,String> calls) { super(Opcodes.ASM9); this.refs=refs;this.calls=calls; }
        void name(String name) { if (name!=null) { if (name.startsWith("[")) descriptor(name); else refs.add(name.replace('/','.')); } }
        void descriptor(String descriptor) {
            if (descriptor!=null) type(org.springframework.asm.Type.getType(descriptor));
        }
        void type(org.springframework.asm.Type type) {
            switch(type.getSort()) {
                case org.springframework.asm.Type.ARRAY -> type(type.getElementType());
                case org.springframework.asm.Type.OBJECT -> name(type.getInternalName());
                case org.springframework.asm.Type.METHOD -> {
                    type(type.getReturnType()); for (var argument:type.getArgumentTypes()) type(argument);
                }
                default -> { }
            }
        }
        void constant(Object value) {
            if(value instanceof org.springframework.asm.Type t) type(t);
            if(value instanceof Handle h) {name(h.getOwner());descriptor(h.getDesc());calls.accept(h.getOwner().replace('/','.'),h.getName()+h.getDesc());}
            if(value instanceof ConstantDynamic c) {
                descriptor(c.getDescriptor());constant(c.getBootstrapMethod());
                for(int i=0;i<c.getBootstrapMethodArgumentCount();i++) constant(c.getBootstrapMethodArgument(i));
            }
        }
        AnnotationVisitor annotation(String descriptor) {
            descriptor(descriptor);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String name,Object value){constant(value);}
                @Override public void visitEnum(String name,String desc,String value){descriptor(desc);}
                @Override public AnnotationVisitor visitAnnotation(String name,String desc){return annotation(desc);}
                @Override public AnnotationVisitor visitArray(String name){return this;}
            };
        }
        @Override public void visit(int version,int access,String name,String signature,String parent,String[] interfaces) {
            name(parent);for(String value:interfaces)name(value);
        }
        @Override public AnnotationVisitor visitAnnotation(String desc,boolean visible){return annotation(desc);}
        @Override public RecordComponentVisitor visitRecordComponent(String name,String desc,String signature) {
            descriptor(desc);return null;
        }
        @Override public FieldVisitor visitField(int access,String name,String desc,String signature,Object value) {
            descriptor(desc);constant(value);
            return new FieldVisitor(Opcodes.ASM9){@Override public AnnotationVisitor visitAnnotation(String d,boolean v){return annotation(d);}};
        }
        @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
            descriptor(desc);if(exceptions!=null)for(String value:exceptions)name(value);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String d,boolean v){return annotation(d);}
                @Override public AnnotationVisitor visitParameterAnnotation(int p,String d,boolean v){return annotation(d);}
                @Override public void visitTypeInsn(int opcode,String type){name(type);}
                @Override public void visitFieldInsn(int opcode,String owner,String name,String d){name(owner);descriptor(d);}
                @Override public void visitMethodInsn(int opcode,String owner,String name,String d,boolean itf){name(owner);descriptor(d);calls.accept(owner.replace('/','.'),name+d);}
                @Override public void visitInvokeDynamicInsn(String name,String d,Handle bootstrap,Object... arguments) {
                    descriptor(d);constant(bootstrap);for(Object value:arguments)constant(value);
                }
                @Override public void visitLdcInsn(Object value){constant(value);}
                @Override public void visitMultiANewArrayInsn(String d,int dimensions){descriptor(d);}
                @Override public void visitTryCatchBlock(Label start,Label end,Label handler,String type){name(type);}
            };
        }
    }
}
