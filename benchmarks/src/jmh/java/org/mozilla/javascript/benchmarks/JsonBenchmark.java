package org.mozilla.javascript.benchmarks;

import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Kit;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.Scriptable;
import org.openjdk.jmh.annotations.*;

@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class JsonBenchmark {
    @State(Scope.Thread)
    public static class JsonState {
        Context cx;
        Scriptable scope;
        String script;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            cx = Context.enter();
            cx.setOptimizationLevel(9);
            cx.setLanguageVersion(Context.VERSION_ES6);
            scope = cx.initStandardObjects();
            script =
                    Kit.readReader(new FileReader("testsrc/benchmarks/micro/benchmark.json"));

        }

        @TearDown(Level.Trial)
        public void tearDown() {
            cx.close();
        }
    }

    @Test
    public void testState() throws IOException {
        JsonState state = new JsonState();
        state.setup();
        System.out.println(state.script.length());
    }

    @Test
    public void testJson() throws IOException
    {
        JsonState state = new JsonState();
        state.setup();

        int i = 0;
        long timeBeforeMs = System.currentTimeMillis();
        while (i < 1000) {
            NativeJSON.parse(state.cx, state.scope, state.script);
            i += 1;
        }
        System.out.println(System.currentTimeMillis() - timeBeforeMs);
    }

    @Benchmark
    @OperationsPerInvocation(250)
    public Object parseJSON(JsonState state) {
        return NativeJSON.parse(state.cx, state.scope, state.script);
    }
}