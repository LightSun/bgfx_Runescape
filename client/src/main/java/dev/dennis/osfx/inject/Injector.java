package dev.dennis.osfx.inject;

import com.google.common.io.ByteStreams;
import dev.dennis.osfx.inject.adapter.*;
import dev.dennis.osfx.inject.hook.*;
import dev.dennis.osfx.inject.mixin.*;
import dev.dennis.osfx.util.MathUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Type;
import org.reflections.Reflections;

import java.io.*;
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

    private static final String CONSTRUCTOR_NAME = "<init>";

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
        Reflections reflections = new Reflections(packageName);
        for (Class<?> mixinClass : reflections.getTypesAnnotatedWith(Mixin.class)) {
            loadMixin(mixinClass);
        }
    }

    public void loadMixin(Class<?> mixinClass) {
        if (!mixinClass.isAnnotationPresent(Mixin.class)) {
            throw new IllegalArgumentException(mixinClass.getName() + " is not a Mixin Class");
        }
        Mixin mixin = mixinClass.getAnnotation(Mixin.class);
        //将json配置的hook 绑定到mixin对应的
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
            boolean isStatic = Modifier.isStatic(field.getModifiers());
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
                        new AddGetterAdapter(delegate, getterName, Type.getMethodDescriptor(fieldType),
                                isStatic, classHook.getName(), fieldName, fieldType.getDescriptor(),
                                null));
            }
            if (field.isAnnotationPresent(Setter.class)) {
                Setter setter = field.getAnnotation(Setter.class);
                String setterName;
                if (setter.value().isEmpty()) {
                    setterName = "set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                } else {
                    setterName = setter.value();
                }
                String setterDesc = Type.getMethodDescriptor(Type.VOID_TYPE, fieldType);
                preCopyAdapterGroup.addAdapter(obfClassName, delegate ->
                        new AddSetterAdapter(delegate, setterName, setterDesc, isStatic, classHook.getName(),
                                fieldName, fieldType.getDescriptor(), null));
            }
        }

        List<Method> methodsToCopy = new ArrayList<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            boolean copy = true;
            if (method.isAnnotationPresent(Getter.class)) {
                addGetterAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Setter.class)) {
                addSetterAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Invoke.class)) {
                addInvokeAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Inject.class)) {
                addInjectCallbackAdapter(mixin, classHook, method);
            } else if (method.isAnnotationPresent(Copy.class)) {
                addCopyMethodAdapter(mixin, classHook, method);
                copy = false;
            } else if (method.isAnnotationPresent(Replace.class)) {
                addReplaceMethodAdapter(mixin, classHook, method);
            }

            if (copy && !Modifier.isAbstract(method.getModifiers())) {
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
        boolean isStatic = method.isAnnotationPresent(Static.class) || Modifier.isStatic(method.getModifiers());
        String owner;
        String name;
        String desc;
        if (isStatic) {
            StaticMethodHook methodHook = hooks.getStaticMethod(replace.value());
            if (methodHook == null) {
                throw new IllegalStateException("No static method hook found for " + replace.value());
            }
            owner = methodHook.getOwner();
            name = methodHook.getName();
            desc = methodHook.getDesc();
        } else {
            MethodHook methodHook = classHook.getMethod(replace.value());
            if (methodHook == null) {
                throw new IllegalStateException("No method hook found for " + mixin.value() + "." + replace.value());
            }
            owner = classHook.getName();
            name = methodHook.getName();
            desc = methodHook.getDesc();
        }
        postCopyAdapterGroup.addAdapter(owner, delegate ->
                new ReplaceMethodAdapter(delegate, name, desc, isStatic, classHook.getName(), method.getName(),
                        Type.getMethodDescriptor(method)));
    }

    private void addCopyMethodAdapter(Mixin mixin, ClassHook classHook, Method method) {
        Copy copy = method.getAnnotation(Copy.class);
        boolean isStatic = method.isAnnotationPresent(Static.class) || Modifier.isStatic(method.getModifiers());
        String owner;
        String name;
        String desc;
        if (isStatic) {
            StaticMethodHook methodHook = hooks.getStaticMethod(copy.value());
            if (methodHook == null) {
                throw new IllegalStateException("No static method hook found for " + copy.value());
            }
            owner = methodHook.getOwner();
            name = methodHook.getName();
            desc = methodHook.getDesc();
        } else {
            MethodHook methodHook = classHook.getMethod(copy.value());
            if (methodHook == null) {
                throw new IllegalStateException("No method hook found for " + mixin.value() + "." + copy.value());
            }
            owner = classHook.getName();
            name = methodHook.getName();
            desc = methodHook.getDesc();
        }

        ClassVisitor delegate = copyAdapterGroup.delegate(classHook.getName());
        copyAdapterGroup.addAdapter(owner, new CopyMethodAdapter(copyAdapterGroup.delegate(owner),
                name, desc, delegate, method.getName()));
    }

    private void addGetterAdapter(Mixin mixin, ClassHook classHook, Method method) {
        if (!Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalStateException("Getter method " + mixin.value() + "." + method.getName()
                    + " must be abstract");
        }
        Getter getter = method.getAnnotation(Getter.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        String methodDesc = Type.getMethodDescriptor(method);
        String fieldOwner;
        String fieldName;
        String fieldDesc;
        Number fieldMultiplier;
        if (isStatic) {
            StaticFieldHook fieldHook = hooks.getStaticField(getter.value());
            if (fieldHook == null) {
                throw new IllegalStateException("No static field hook found for " + getter.value());
            }
            fieldOwner = fieldHook.getOwner();
            fieldName = fieldHook.getName();
            fieldDesc = fieldHook.getDesc();
            fieldMultiplier = fieldHook.getMultiplier();
        } else {
            FieldHook fieldHook = classHook.getField(getter.value());
            if (fieldHook == null) {
                throw new IllegalStateException("No field hook found for " + mixin.value() + "."
                        + getter.value());
            }
            fieldOwner = classHook.getName();
            fieldName = fieldHook.getName();
            fieldDesc = fieldHook.getDesc();
            fieldMultiplier = fieldHook.getMultiplier();
        }
        preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                new AddGetterAdapter(delegate, method.getName(), methodDesc, isStatic,
                        fieldOwner, fieldName, fieldDesc, fieldMultiplier));
    }

    private void addSetterAdapter(Mixin mixin, ClassHook classHook, Method method) {
        if (!Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalStateException("Setter method " + mixin.value() + "." + method.getName()
                    + " must be abstract");
        }
        Setter setter = method.getAnnotation(Setter.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        String fieldOwner;
        String fieldName;
        String fieldDesc;
        Number fieldMultiplier;
        if (isStatic) {
            StaticFieldHook fieldHook = hooks.getStaticField(setter.value());
            if (fieldHook == null) {
                throw new IllegalStateException("No static field hook found for " + setter.value());
            }
            fieldOwner = fieldHook.getOwner();
            fieldName = fieldHook.getName();
            fieldDesc = fieldHook.getDesc();
            fieldMultiplier = fieldHook.getMultiplier();
        } else {
            FieldHook fieldHook = classHook.getField(setter.value());
            if (fieldHook == null) {
                throw new IllegalStateException("No field hook found for " + mixin.value() + "."
                        + setter.value());
            }
            fieldOwner = classHook.getName();
            fieldName = fieldHook.getName();
            fieldDesc = fieldHook.getDesc();
            fieldMultiplier = fieldHook.getMultiplier();
        }
        Type fieldType = Type.getType(fieldDesc);
        Number inversedMultiplier;
        if (fieldMultiplier != null) {
            // The field multiplier is for decoding the value
            // so we must inverse it to encode it
            if (fieldType.equals(Type.INT_TYPE)) {
                inversedMultiplier = MathUtil.modInverse(fieldMultiplier.intValue());
            } else if (fieldType.equals(Type.LONG_TYPE)) {
                inversedMultiplier = MathUtil.modInverse(fieldMultiplier.longValue());
            } else {
                throw new IllegalStateException("Setter method " + mixin.value() + "." + method.getName()
                        + "'s hook has a multiplier with the wrong type");
            }
        } else {
            inversedMultiplier = null;
        }
        preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                new AddSetterAdapter(delegate, method.getName(), Type.getMethodDescriptor(method), isStatic,
                        fieldOwner, fieldName, fieldDesc, inversedMultiplier));
    }

    private void addInvokeAdapter(Mixin mixin, ClassHook classHook, Method method) {
        if (!Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalStateException("Invoke method " + mixin.value() + "." + method.getName()
                    + " must be abstract");
        }
        Invoke invoke = method.getAnnotation(Invoke.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        if (isStatic) {
            StaticMethodHook methodHook = hooks.getStaticMethod(invoke.value());
            if (methodHook == null) {
                throw new IllegalStateException("No static method hook found for " + invoke.value());
            }
            throw new UnsupportedOperationException();
        } else {
            MethodHook methodHook = classHook.getMethod(invoke.value());
            if (methodHook == null) {
                throw new IllegalStateException("No method hook found for " + mixin.value() + "."
                        + invoke.value());
            }
            preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                    new AddInvokeAdapter(delegate, method.getName(), Type.getMethodDescriptor(method),
                            classHook.getName(), methodHook.getName(), methodHook.getDesc(),
                            methodHook.getDummyValue()));
        }
    }

    private void addInjectCallbackAdapter(Mixin mixin, ClassHook classHook, Method method) {
        Inject inject = method.getAnnotation(Inject.class);
        boolean isStatic = method.isAnnotationPresent(Static.class);
        if (isStatic) {
            throw new UnsupportedOperationException();
        }
        String hookName = inject.value();
        MethodHook methodHook;
        if (hookName.equals(CONSTRUCTOR_NAME)) {
            methodHook = new MethodHook(CONSTRUCTOR_NAME, null);
        } else {
            methodHook = classHook.getMethod(hookName);
        }
        if (methodHook == null) {
            throw new IllegalStateException("No method hook found for " + mixin.value() + "." + hookName);
        }
        preCopyAdapterGroup.addAdapter(classHook.getName(), delegate ->
                new AddInjectCallbackAdapter(delegate, methodHook.getName(), methodHook.getDesc(),
                        method.getName(), Type.getMethodDescriptor(method), inject.end()));
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
        //将input 的jar的每个类加入 classes (map)
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
    //------------------ heaven7 ----------------
    //h7 test
    public void inject(String className, Path outputPath){
        Map<String, byte[]> classes = new HashMap<>();
        ClassReader reader = null;
        try {
            reader = new ClassReader(className);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
//TODO test game engine
      /*  ClassHook gameEngineHook = hooks.getClassHook(GAME_ENGINE_HOOK_NAME);
        if (gameEngineHook == null) {
            throw new IllegalStateException(GAME_ENGINE_HOOK_NAME + " hook required");
        }
        Type gameEngineType = Type.getObjectType(gameEngineHook.getName());
        AdapterGroup appletToPanelGroup = new AdapterGroup();
        for (String cn : classes.keySet()) {
            appletToPanelGroup.addAdapter(cn, delegate -> new AppletToPanelAdapter(delegate, gameEngineType));
        }*/
        List<AdapterGroup> adapterGroups = Arrays.asList(
                //appletToPanelGroup,
                preCopyAdapterGroup,
                copyAdapterGroup,
                postCopyAdapterGroup
        );

        classes.put(className, reader.b);
        for (AdapterGroup group : adapterGroups) {
            group.apply(classes);
        }

        try {
            FileOutputStream outputStream = new FileOutputStream(outputPath.toFile());
            try (JarOutputStream jarOut = new JarOutputStream(outputStream, new Manifest())) {
                for (Map.Entry<String, byte[]> classEntry : classes.entrySet()) {
                    String className2 = classEntry.getKey();
                    JarEntry newEntry = new JarEntry(className2 + ".class");
                    jarOut.putNextEntry(newEntry);
                    jarOut.write(classEntry.getValue());
                    jarOut.closeEntry();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }
}
