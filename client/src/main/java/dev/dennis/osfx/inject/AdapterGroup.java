package dev.dennis.osfx.inject;

import org.mutabilitydetector.asm.NonClassloadingClassWriter;
import org.mutabilitydetector.asm.typehierarchy.ConcurrentMapCachingTypeHierarchyReader;
import org.mutabilitydetector.asm.typehierarchy.IsAssignableFromCachingTypeHierarchyReader;
import org.mutabilitydetector.asm.typehierarchy.TypeHierarchyReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class AdapterGroup {
    private final Map<String, ClassWriter> writers;

    private final Map<String, ClassVisitor> adapters;

    private final ClientTypeHierarchyReader clientTypeHierarchyReader;

    private final TypeHierarchyReader cachedTypeHierarchyReader;

    public AdapterGroup() {
        this.writers = new HashMap<>();
        this.adapters = new HashMap<>();
        this.clientTypeHierarchyReader = new ClientTypeHierarchyReader();
        this.cachedTypeHierarchyReader = new IsAssignableFromCachingTypeHierarchyReader(
                new ConcurrentMapCachingTypeHierarchyReader(this.clientTypeHierarchyReader)
        );
    }

    public void apply(Map<String, byte[]> classes) {
        clientTypeHierarchyReader.setClasses(classes);
        List<String> classesToWrite = new ArrayList<>();
        for (Map.Entry<String, ClassVisitor> adapterEntry : adapters.entrySet()) {
            //adapter  注册的class
            String className = adapterEntry.getKey();
            ClassVisitor adapter = adapterEntry.getValue();
            ClassWriter writer = writers.get(className);
            if (writer == null) {
                continue;
            }
            byte[] classData = classes.get(className);
            if (classData == null) {
                System.err.println(className + " Class not found");
                //throw new IllegalStateException(className + " Class not found");'
                continue;
            }
            ClassReader classReader = new ClassReader(classData);
            classReader.accept(adapter, ClassReader.EXPAND_FRAMES);

            classesToWrite.add(className);
        }
        //给每个class重新生成新的代码 (注入后的)
        for (String className : classesToWrite) {
            classes.put(className, writers.get(className).toByteArray());
        }
    }

    public void addAdapter(String className, ClassVisitor adapter) {
        adapters.put(className, adapter);
    }

    public void addAdapter(String className, Function<ClassVisitor, ClassVisitor> adapterFunc) {
        addAdapter(className, className, adapterFunc);
    }

    public void addAdapter(String className, String delegateClassName,
                           Function<ClassVisitor, ClassVisitor> adapterFunc) {
        addAdapter(className, adapterFunc.apply(delegate(delegateClassName)));
    }

    public ClassVisitor delegate(String className) {
        return adapters.computeIfAbsent(className, this::getWriter);
    }

    private ClassWriter getWriter(String className) {
        return writers.computeIfAbsent(className, c ->
                new NonClassloadingClassWriter(null, ClassWriter.COMPUTE_FRAMES, cachedTypeHierarchyReader));
    }
}
