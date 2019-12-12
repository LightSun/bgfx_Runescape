package dev.dennis.mixin.inject;

import com.google.common.io.ByteStreams;
import dev.dennis.mixin.*;
import dev.dennis.mixin.hook.*;
import dev.dennis.mixin.inject.asm.*;
import org.objectweb.asm.Type;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class Injector {
    private static final String GAME_ENGINE_HOOK_NAME = "GameEngine";

    private final Hooks hooks;

    private final AdapterGroup preCopyAdapterGroup;

    private final AdapterGroup copyAdapterGroup;

    private final AdapterGroup postCopyAdapterGroup;

    public Injector(Hooks hooks) {
        this.hooks = hooks;
        this.preCopyAdapterGroup = new AdapterGroup();
        this.copyAdapterGroup = new AdapterGroup();
        this.postCopyAdapterGroup = new AdapterGroup();
    }

    public void loadMixins(String packageName) {

    }

    public void loadMixin(Class<?> mixinClass) throws IOException {
        if (!mixinClass.isAnnotationPresent(Mixin.class)) {
            throw new IllegalArgumentException(mixinClass.getName() + " is not a Mixin Class");
        }
        Mixin mixin = mixinClass.getAnnotation(Mixin.class);

        ClassHook classHook = hooks.getClassHook(mixin.value());
        if (classHook == null) {
            throw new IllegalStateException("No class hook found for " + mixin.value());
        }

        String obfClassName = classHook.getName();

        for (Class<?> interfaceClass : mixinClass.getInterfaces()) {
            preCopyAdapterGroup.addAdapter(obfClassName, delegate -> new AddInterfaceAdapter(delegate, interfaceClass));
        }

        for (Field field : mixinClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(Shadow.class)) {
                continue;
            }
            String fieldName = field.getName();
            Type fieldType = Type.getType(field.getType());
            preCopyAdapterGroup.addAdapter(obfClassName, delegate -> new AddFieldAdapter(delegate, field.getModifiers(),
                    fieldName, fieldType.getDescriptor()));
            if (field.isAnnotationPresent(Getter.class)) {
                Getter getter = field.getAnnotation(Getter.class);
                String getterName;
                if (getter.value().isEmpty()) {
                    getterName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                } else {
                    getterName = getter.value();
                }
                preCopyAdapterGroup.addAdapter(obfClassName, delegate ->
                        new AddGetterAdapter(getterName, Type.getMethodDescriptor(fieldType),
                                fieldName, fieldType.getInternalName(), null, delegate));
            }
            if (field.isAnnotationPresent(Setter.class)) {
                Setter setter = field.getAnnotation(Setter.class);
                String setterName;
                if (setter.value().isEmpty()) {
                    setterName = "set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                } else {
                    setterName = setter.value();
                }
                preCopyAdapterGroup.addAdapter(obfClassName, delegate ->
                        new AddSetterAdapter(delegate, setterName, fieldName, fieldType.getInternalName(),
                                null));
            }
        }

        List<Method> methodsToCopy = new ArrayList<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Getter.class)) {
                addGetterAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Inject.class)) {
                addInjectCallbackAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Copy.class)) {
                addCopyMethodAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Replace.class)) {
                addReplaceMethodAdapter(mixin, classHook, method);
            }

            if (!Modifier.isAbstract(method.getModifiers())) {
                methodsToCopy.add(method);
            }
        }
        if (!methodsToCopy.isEmpty()) {
            preCopyAdapterGroup.addAdapter(obfClassName, delegate ->
                    new AddMethodsAdapter(delegate, hooks, mixinClass, methodsToCopy));
        }

    }

    private void addReplaceMethodAdapter(Mixin mixin, ClassHook classHook, Method method) {
        Replace replace = method.getAnnotation(Replace.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        if (isStatic) {
            throw new NotImplementedException();
        } else {
            MethodHook methodHook = classHook.getMethod(replace.value());
            if (methodHook == null) {
                throw new IllegalStateException("No method hook found for " + mixin.value() + "." + replace.value());
            }

            postCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                    new ReplaceMethodAdapter(delegate, methodHook.getName(), methodHook.getDesc(),
                            classHook.getName(), method.getName(), Type.getMethodDescriptor(method)));
        }
    }

    private void addCopyMethodAdapter(Mixin mixin, ClassHook classHook, Method method) {
        Copy copy = method.getAnnotation(Copy.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        String owner;
        String name;
        String desc;
        if (isStatic) {
            throw new NotImplementedException();
        } else {
            MethodHook methodHook = classHook.getMethod(copy.value());
            if (methodHook == null) {
                throw new IllegalStateException("No method hook found for " + mixin.value() + "." + copy.value());
            }
            owner = classHook.getName();
            name = methodHook.getName();
            desc = methodHook.getDesc();
        }

        copyAdapterGroup.addAdapter(classHook.getName(),
                new CopyMethodAdapter(copyAdapterGroup.delegate(owner), name, desc,
                        copyAdapterGroup.delegate(classHook.getName()), method.getName()));
    }

    private void addGetterAdapter(Mixin mixin, ClassHook classHook, Method method) {
        if (!Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalStateException("Getter method " + mixin.value() + "." + method.getName()
                    + " must be abstract");
        }
        Getter getter = method.getAnnotation(Getter.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        String methodDesc = Type.getMethodDescriptor(method);
        if (isStatic) {
            StaticFieldHook fieldHook = hooks.getStaticField(getter.value());
            if (fieldHook == null) {
                throw new IllegalStateException("No static field hook found for " + getter.value());
            }
            preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                    new AddStaticGetterAdapter(delegate, method.getName(), methodDesc, fieldHook));
        } else {
            FieldHook fieldHook = classHook.getField(getter.value());
            if (fieldHook == null) {
                throw new IllegalStateException("No field hook found for " + mixin.value() + "."
                        + getter.value());
            }
            preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                    new AddGetterAdapter(delegate, method.getName(), methodDesc, fieldHook));
        }
    }

    private void addInjectCallbackAdapter(Mixin mixin, ClassHook classHook, Method method) {
        Inject inject = method.getAnnotation(Inject.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        if (isStatic) {
            throw new NotImplementedException();
        }
        MethodHook methodHook = classHook.getMethod(inject.value());
        if (methodHook == null) {
            throw new IllegalStateException("No method hook found for " + mixin.value() + "." + inject.value());
        }
        preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                new AddInjectCallbackAdapter(delegate, method, methodHook.getName(), methodHook.getDesc(),
                        inject.end()));
    }

    public void inject(Path jarPath, Path outputPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            inject(jarFile, new FileOutputStream(outputPath.toFile()));
        }
    }

    public void inject(JarFile jarFile, OutputStream outputStream) throws IOException {
        ClassHook gameEngineHook = hooks.getClassHook(GAME_ENGINE_HOOK_NAME);
        if (gameEngineHook == null) {
            throw new IllegalStateException(GAME_ENGINE_HOOK_NAME + " hook required");
        }
        Type gameEngineType = Type.getObjectType(gameEngineHook.getName());

        Map<String, byte[]> classes = new HashMap<>();

        for (JarEntry entry : Collections.list(jarFile.entries())) {
            String entryName = entry.getName();
            if (entryName.endsWith(".class")) {
                String className = entryName.substring(0, entryName.length() - 6);
                classes.put(className, ByteStreams.toByteArray(jarFile.getInputStream(entry)));
            }
        }

        AdapterGroup appletToPanelGroup = new AdapterGroup();
        for (String className : classes.keySet()) {
            appletToPanelGroup.addAdapter(className, delegate -> new AppletToPanelAdapter(delegate, gameEngineType));
        }

        List<AdapterGroup> adapterGroups = Arrays.asList(
                appletToPanelGroup,
                preCopyAdapterGroup,
                copyAdapterGroup,
                postCopyAdapterGroup
        );

        for (AdapterGroup group : adapterGroups) {
            group.apply(classes);
        }

        try (JarOutputStream jarOut = new JarOutputStream(outputStream, new Manifest())) {
            for (Map.Entry<String, byte[]> classEntry : classes.entrySet()) {
                String className = classEntry.getKey();
                JarEntry newEntry = new JarEntry(className + ".class");
                jarOut.putNextEntry(newEntry);
                jarOut.write(classEntry.getValue());
                jarOut.closeEntry();
            }
        }
    }
}
