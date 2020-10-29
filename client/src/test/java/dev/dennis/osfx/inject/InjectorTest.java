package dev.dennis.osfx.inject;

import com.google.gson.Gson;
import dev.dennis.osfx.inject.hook.Hooks;
import dev.dennis.osfx.inject.impl.FontImpl;
import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Paths;

public class InjectorTest {
    public static void main(String[] args) throws Exception {
        String version = "187";

        Hooks hooks = loadHooks(version);

        Injector injector = new Injector(hooks);
        injector.loadMixins("dev.dennis.osfx.mixin");

        long start = System.currentTimeMillis();
        injector.inject(Paths.get(version + ".jar"), Paths.get(version + "_injected.jar"));
        long end = System.currentTimeMillis();
        System.out.println("elapsed: " + (end - start));
    }

    private static Hooks loadHooks(String version) {
        Gson gson = new Gson();
        Reader reader = new InputStreamReader(HooksTest.class.getResourceAsStream("/hooks/" + version + ".json"));
        return gson.fromJson(reader, Hooks.class);
    }

    //h7
    @Test
    public void test1(){
        String version = "187_h7";

        Hooks hooks = loadHooks(version);

        Injector injector = new Injector(hooks);
        injector.loadMixins("dev.dennis.osfx.mixin");

        long start = System.currentTimeMillis();
        injector.inject(FontImpl.class.getName(), Paths.get(version + "_injected.jar"));
        System.out.println("test1: inject elapsed: " + (System.currentTimeMillis() - start));
    }
}
