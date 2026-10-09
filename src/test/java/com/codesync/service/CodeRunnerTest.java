package com.codesync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** These really compile and run Java, so they need a JDK (not a JRE) - which Code-Sync requires anyway. */
class CodeRunnerTest {

    private final CodeRunner runner = new CodeRunner();

    @Test
    void runsHelloWorld() {
        var r = runner.run("java",
                "public class Main { public static void main(String[] a) { System.out.println(\"hi\"); } }", "");
        assertEquals("OK", r.status());
        assertEquals("hi", r.output().trim());
    }

    @Test
    void reportsCompileErrors() {
        var r = runner.run("java", "public class Main { void f() { int x = \"s\"; } }", "");
        assertEquals("COMPILE_ERROR", r.status());
        assertTrue(r.output().contains("Main.java:1"));
    }

    @Test
    void stopsInfiniteLoops() {
        var r = runner.run("java",
                "public class Main { public static void main(String[] a) { while (true) { } } }", "");
        assertEquals("TIMEOUT", r.status());
    }

    @Test
    void readsInput() {
        var r = runner.run("java", "import java.util.*; public class Main { public static void main(String[] a) "
                + "{ System.out.println(new Scanner(System.in).nextInt() * 2); } }", "21\n");
        assertEquals("42", r.output().trim());
    }
}
