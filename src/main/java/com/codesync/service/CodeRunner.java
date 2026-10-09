package com.codesync.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Compiles and runs a room's code in a throw-away folder.
 *
 * <p>Limits: compile 15 s, run 5 s, 64 KB of output, at most 2 programs at once, secrets stripped from the
 * environment, folder deleted afterwards. These are process-level limits only. They stop accidents and
 * infinite loops, but they are NOT a full sandbox: in production run the whole server inside a locked-down
 * container (Phase 5) with a memory limit, no network and a non-root user.
 */
@Service
public class CodeRunner {

    /** status: OK, COMPILE_ERROR, RUNTIME_ERROR, TIMEOUT, OUTPUT_LIMIT, BUSY or ERROR. */
    public record RunResult(String status, String output, int exitCode, long millis, boolean truncated) {
    }

    private record Stage(int exitCode, String output, boolean timedOut, boolean truncated) {
    }

    public static final int MAX_SOURCE_CHARS = 100_000;
    public static final int MAX_STDIN_CHARS = 10_000;
    static final int MAX_OUTPUT_BYTES = 64 * 1024;
    static final int COMPILE_SECONDS = 15;
    static final int RUN_SECONDS = 5;
    private static final int MAX_PARALLEL = 2;

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Pattern PUBLIC_TYPE =
            Pattern.compile("\\bpublic\\s+(?:(?:final|abstract|sealed|strictfp)\\s+)*(?:class|record|interface|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern ANY_TYPE = Pattern.compile("\\b(?:class|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;", Pattern.MULTILINE);
    private static final Pattern SECRET_ENV =
            Pattern.compile("(?i).*(KEY|SECRET|TOKEN|CREDENTIAL|PASSWORD|PASSWD|FIREBASE|GOOGLE_APPLICATION).*");
    private static final Set<String> DROP_ENV = Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private final Semaphore slots = new Semaphore(MAX_PARALLEL);

    public RunResult run(String language, String source, String stdin) {
        if (source.length() > MAX_SOURCE_CHARS) {
            return fail("ERROR", "The code is too large to run.", 0);
        }
        String input = stdin == null ? "" : stdin.substring(0, Math.min(stdin.length(), MAX_STDIN_CHARS));
        if (!slots.tryAcquire()) {
            return fail("BUSY", "The server is running other programs right now. Try again in a few seconds.", 0);
        }
        long start = System.nanoTime();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("codesync-run-");
            return switch (language) {
                case "java" -> runJava(dir, source, input, start);
                case "python" -> runPython(dir, source, input, start);
                default -> fail("ERROR", "Running " + language + " is not supported.", millisSince(start));
            };
        } catch (IOException e) {
            return fail("ERROR", "Could not start the program: " + e.getMessage(), millisSince(start));
        } finally {
            slots.release();
            deleteQuietly(dir);
        }
    }

    // ---------- Java ----------

    private RunResult runJava(Path dir, String source, String stdin, long start) throws IOException {
        Path javac = jdkTool("javac");
        Path java = jdkTool("java");
        if (!Files.exists(javac)) {
            return fail("ERROR", "This server has no Java compiler (javac). It must run on a full JDK 21, not a JRE.",
                    millisSince(start));
        }

        String clean = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
        String publicName = firstGroup(PUBLIC_TYPE, clean);
        String pkg = firstGroup(PACKAGE, clean);
        String mainClass = publicName != null ? publicName : classBeforeMain(clean);
        String fileName = (publicName != null ? publicName : "Main") + ".java";
        Files.writeString(dir.resolve(fileName), source, StandardCharsets.UTF_8);

        Stage compile = exec(List.of(javac.toString(), "-encoding", "UTF-8", "-proc:none", "-d", ".", fileName), dir, "", COMPILE_SECONDS);
        if (compile.timedOut()) {
            return result("TIMEOUT", compile.output() + "\n[Stopped: compiling took longer than " + COMPILE_SECONDS + " seconds]",
                    -1, start, compile.truncated());
        }
        if (compile.exitCode() != 0) {
            return result("COMPILE_ERROR", compile.output(), compile.exitCode(), start, compile.truncated());
        }

        String runName = (pkg != null ? pkg + "." : "") + mainClass;
        Stage run = exec(List.of(java.toString(), "-Xmx128m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-cp", dir.toString(), runName), dir, stdin, RUN_SECONDS);
        return finish(run, start, "Main method not found. Add: public static void main(String[] args)");
    }

    /** For code without a public class: the last class declared before "void main", else Main. */
    private static String classBeforeMain(String clean) {
        int mainAt = clean.indexOf("void main");
        String found = "Main";
        if (mainAt >= 0) {
            Matcher m = ANY_TYPE.matcher(clean);
            while (m.find() && m.start() < mainAt) {
                found = m.group(1);
            }
        }
        return found;
    }

    // ---------- Python ----------

    private RunResult runPython(Path dir, String source, String stdin, long start) throws IOException {
        Files.writeString(dir.resolve("main.py"), source, StandardCharsets.UTF_8);
        String python = System.getenv("CODESYNC_PYTHON");
        if (python == null || python.isBlank()) {
            python = WINDOWS ? "python" : "python3";
        }
        Stage run;
        try {
            run = exec(List.of(python, "-u", "main.py"), dir, stdin, RUN_SECONDS);
        } catch (IOException e) {
            return fail("ERROR", "Python is not installed on the server (looked for '" + python + "').", millisSince(start));
        }
        return finish(run, start, null);
    }

    // ---------- shared ----------

    private RunResult finish(Stage run, long start, String missingMainHint) {
        String out = run.output();
        if (missingMainHint != null && out.contains("Could not find or load main class")) {
            out += "\n" + missingMainHint;
        }
        if (run.timedOut()) {
            return result("TIMEOUT", out + "\n[Stopped: the program ran longer than " + RUN_SECONDS + " seconds]",
                    -1, start, run.truncated());
        }
        if (run.truncated()) {
            return result("OUTPUT_LIMIT", out + "\n[Stopped: more than " + (MAX_OUTPUT_BYTES / 1024) + " KB of output]",
                    run.exitCode(), start, true);
        }
        return result(run.exitCode() == 0 ? "OK" : "RUNTIME_ERROR", out, run.exitCode(), start, false);
    }

    /** Runs one command with a time limit and an output cap. Merges stderr into stdout. */
    private Stage exec(List<String> command, Path dir, String stdin, int seconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> DROP_ENV.contains(k) || SECRET_ENV.matcher(k).matches());
        Process p = pb.start();

        byte[] input = stdin.getBytes(StandardCharsets.UTF_8);
        Thread.ofVirtual().start(() -> {
            try (OutputStream os = p.getOutputStream()) {
                os.write(input);
            } catch (IOException ignored) {
                // the program closed its input early; that is fine
            }
        });

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        AtomicBoolean tooMuch = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> {
            byte[] chunk = new byte[4096];
            try (InputStream is = p.getInputStream()) {
                int n;
                while ((n = is.read(chunk)) != -1) {
                    synchronized (buf) {
                        int room = Math.max(0, MAX_OUTPUT_BYTES - buf.size());
                        buf.write(chunk, 0, Math.min(n, room));
                        if (n > room) {
                            tooMuch.set(true);
                            killTree(p);
                            return;
                        }
                    }
                }
            } catch (IOException ignored) {
                // pipe closed because the process was killed
            }
        });

        boolean finished;
        try {
            finished = p.waitFor(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            killTree(p);
        }
        try {
            reader.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int exit = -1;
        if (finished) {
            exit = p.exitValue();
        }
        String text;
        synchronized (buf) {
            text = buf.toString(StandardCharsets.UTF_8);
        }
        return new Stage(exit, text, !finished, tooMuch.get());
    }

    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static Path jdkTool(String name) {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? name + ".exe" : name);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static RunResult result(String status, String output, int exit, long start, boolean truncated) {
        return new RunResult(status, output, exit, millisSince(start), truncated);
    }

    private static RunResult fail(String status, String message, long millis) {
        return new RunResult(status, message, -1, millis, false);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp folder; the OS cleans it eventually
        }
    }
}
