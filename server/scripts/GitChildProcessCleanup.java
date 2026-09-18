import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Java 25 source-file runner for the Linux Git child-process gates and their validators. */
public final class GitChildProcessCleanup {
    private static final String TEST_CLASS = "com.plainbase.frameworks.git.GitExecutorZombieNativeTest";
    private static final String TEST_METHOD = "reparentedZombieCompletesInvocation";
    private static final String TESTCASE_NAME = TEST_METHOD + "()";
    private static final long POSITIVE_DEADLINE_SECONDS = 120;
    private static final long POSITIVE_KILL_GRACE_SECONDS = 10;
    private static final long FORCED_DEADLINE_SECONDS = 10;
    private static final long FORCED_KILL_GRACE_SECONDS = 2;
    private static final long CLEANUP_SECONDS = 15;
    private static final long POLL_MILLIS = 25;
    private static final ErrorHandler QUIET_XML_ERRORS = new ErrorHandler() {
        @Override public void warning(SAXParseException exception) {}
        @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
    };

    private enum Mode {
        JVM("jvm", "jvm", POSITIVE_DEADLINE_SECONDS, POSITIVE_KILL_GRACE_SECONDS),
        NATIVE("native", "runtime", POSITIVE_DEADLINE_SECONDS, POSITIVE_KILL_GRACE_SECONDS),
        FORCED("forced", "jvm", FORCED_DEADLINE_SECONDS, FORCED_KILL_GRACE_SECONDS);

        final String name;
        final String expectedRuntime;
        final long deadlineSeconds;
        final long killGraceSeconds;

        Mode(String name, String expectedRuntime, long deadlineSeconds, long killGraceSeconds) {
            this.name = name;
            this.expectedRuntime = expectedRuntime;
            this.deadlineSeconds = deadlineSeconds;
            this.killGraceSeconds = killGraceSeconds;
        }
    }

    private record Config(Mode mode, Path reportRoot, Path java, String classpath, Path nativeImage, Path uidDir) {}

    private record Identity(long pid, long startTicks, ProcessHandle handle) {}

    private record Launch(List<String> command, Path stage, Path home, Path tmp, Path xml, Path forcedEvidence) {}

    private record Execution(
            Integer exitCode,
            Map<Long, Identity> identities,
            boolean descendantCaptured,
            List<String> failures
    ) {}

    private record NativeSummary(
            long containersFound,
            long containersSkipped,
            long containersStarted,
            long containersAborted,
            long containersSuccessful,
            long containersFailed,
            long testsFound,
            long testsStarted,
            long testsSucceeded,
            long testsAborted,
            long testsFailed,
            long testsSkipped
    ) {}

    private GitChildProcessCleanup() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        run(parse(args));
    }

    private static Config parse(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("missing arguments; use --self-test or the gate options");
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> known = Set.of("--mode", "--report-dir", "--java", "--classpath", "--native-image", "--uid-dir");
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (!known.contains(option)) throw new IllegalArgumentException("unknown or misplaced argument: " + option);
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            if (values.put(option, args[++index]) != null) throw new IllegalArgumentException("duplicate argument: " + option);
        }
        Mode mode = switch (required(values, "--mode")) {
            case "jvm" -> Mode.JVM;
            case "native" -> Mode.NATIVE;
            case "forced" -> Mode.FORCED;
            default -> throw new IllegalArgumentException("--mode must be jvm, native, or forced");
        };
        Path reportRoot = absolutePath(required(values, "--report-dir"));
        if (mode == Mode.NATIVE) {
            reject(values, "--java", "--classpath");
            return new Config(mode, reportRoot, null, null, absolutePath(required(values, "--native-image")),
                    absolutePath(required(values, "--uid-dir")));
        }
        reject(values, "--native-image", "--uid-dir");
        return new Config(mode, reportRoot, absolutePath(required(values, "--java")),
                required(values, "--classpath"), null, null);
    }

    private static void reject(Map<String, String> values, String... names) {
        for (String name : names) if (values.containsKey(name)) throw new IllegalArgumentException(name + " is not valid for this mode");
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing or blank " + name);
        return value;
    }

    private static Path absolutePath(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid path: " + value, failure);
        }
    }

    private static void run(Config config) throws Exception {
        Files.createDirectories(config.reportRoot());
        Path report = Files.createTempDirectory(config.reportRoot(), "run-");
        makePrivate(report);
        Path home = report.resolve("home");
        Path tmp = report.resolve("tmp");
        Files.createDirectories(home);
        Files.createDirectories(tmp);
        makePrivate(home);
        makePrivate(tmp);
        Execution execution = null;
        try {
            Launch launch = prepare(config, report, home, tmp);
            execution = execute(launch.command(), report, config.mode());
            if (!execution.failures().isEmpty()) throw new IllegalStateException(String.join("; ", execution.failures()));
            if (execution.exitCode() == null) throw new IllegalStateException("namespace wrapper exited without an exit code");
            Files.writeString(report.resolve("exit.txt"), execution.exitCode() + "\n");
            switch (config.mode()) {
                case JVM -> require(execution.exitCode() == 0, "JVM G3z launcher failed with exit " + execution.exitCode());
                case NATIVE -> {
                    require(execution.exitCode() == 0, "native G3z launcher failed with exit " + execution.exitCode());
                    validateNative(report.resolve("stdout.log"), launch.xml());
                }
                case FORCED -> validateForced(launch.forcedEvidence(), execution);
            }
            deleteOwned(report, launch.stage(), launch.home(), launch.tmp());
            writeSummary(report, config.mode(), execution, null);
        } catch (Exception | Error thrown) {
            if (execution != null && execution.exitCode() != null) {
                Files.writeString(report.resolve("exit.txt"), execution.exitCode() + "\n");
            }
            writeSummary(report, config.mode(), execution, thrown);
            System.err.println("G3z failure report retained at " + report);
            throw thrown;
        }
    }

    private static Launch prepare(Config config, Path report, Path home, Path tmp) throws IOException {
        require(Files.isRegularFile(config.mode() == Mode.NATIVE ? config.nativeImage() : config.java()),
                "configured executable is missing");
        if (config.mode() != Mode.NATIVE) {
            require(Files.isExecutable(config.java()), "configured Java executable is not executable");
            List<String> child = new ArrayList<>();
            child.add(config.java().toString());
            child.add("--enable-native-access=ALL-UNNAMED");
            addProperties(child, config.mode(), home, tmp);
            Path forcedEvidence = config.mode() == Mode.FORCED ? report.resolve("forced") : null;
            if (forcedEvidence != null) {
                Files.createDirectories(forcedEvidence);
                child.add("-Dplainbase.test.g3z.forced.evidence=" + forcedEvidence);
            }
            child.add("-cp");
            child.add(config.classpath());
            child.add(config.mode() == Mode.JVM
                    ? "com.plainbase.frameworks.git.G3zJvmLauncher"
                    : "com.plainbase.frameworks.git.G3zForcedTimeoutLauncher");
            return new Launch(child, null, home, tmp, null, forcedEvidence);
        }
        require(Files.isExecutable(config.nativeImage()), "nativeTestCompile output is not executable");
        require(Files.isDirectory(config.uidDir()), "nativeTestList UID directory is missing");
        Path stage = report.resolve("stage");
        Path selector = report.resolve("selector");
        Path xml = report.resolve("xml");
        Files.createDirectories(stage);
        Files.createDirectories(selector);
        Files.createDirectories(xml);
        copyNativeFiles(config.nativeImage(), stage);
        selectUid(config.uidDir(), selector);
        Path executable = stage.resolve(config.nativeImage().getFileName().toString());
        List<String> child = new ArrayList<>();
        child.add(executable.toString());
        addProperties(child, config.mode(), home, tmp);
        child.add("-Djunit.platform.listeners.uid.tracking.output.dir=" + selector);
        child.add("--xml-output-dir");
        child.add(xml.toString());
        return new Launch(child, stage, home, tmp, xml, null);
    }

    private static void addProperties(List<String> child, Mode mode, Path home, Path tmp) {
        child.add("-Dplainbase.test.g3z.pid1=true");
        child.add("-Dplainbase.test.g3z.expected-runtime=" + mode.expectedRuntime);
        child.add("-Dplainbase.test.g3z.expected-uid=" + numericIdentity("-u"));
        child.add("-Dplainbase.test.g3z.expected-gid=" + numericIdentity("-g"));
        child.add("-Duser.home=" + home);
        child.add("-Djava.io.tmpdir=" + tmp);
    }

    private static String numericIdentity(String option) {
        Path id = trustedTool("id");
        try {
            Process process = new ProcessBuilder(id.toString(), option).redirectErrorStream(true).start();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(id + " " + option + " exceeded 5 seconds");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            require(process.exitValue() == 0 && output.matches("[0-9]+"), "trusted id returned invalid identity: " + output);
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("identity lookup was interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("could not run trusted id", failure);
        }
    }

    private static Path trustedTool(String name) {
        for (Path candidate : List.of(Path.of("/usr/bin", name), Path.of("/bin", name))) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        }
        throw new IllegalStateException("G3z requires trusted absolute tool /usr/bin/" + name + " or /bin/" + name);
    }

    private static void copyNativeFiles(Path image, Path stage) throws IOException {
        Path parent = image.getParent();
        require(parent != null, "native executable has no parent directory");
        try (var files = Files.list(parent)) {
            for (Path source : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (!Files.isRegularFile(source)) continue;
                Path destination = stage.resolve(source.getFileName().toString());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.isExecutable(source)) require(Files.isExecutable(destination), "native staging lost execute permission");
            }
        }
    }

    private static void selectUid(Path uidDir, Path selector) throws IOException {
        List<String> ids = new ArrayList<>();
        try (var files = Files.walk(uidDir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("junit-platform-unique-ids") && name.endsWith(".txt")) {
                    for (String line : Files.readAllLines(file)) if (!line.isBlank()) ids.add(line);
                }
            }
        }
        String classMarker = "[class:" + TEST_CLASS + "]";
        String methodMarker = "[method:" + TEST_METHOD + "(";
        List<String> selected = ids.stream().filter(id -> id.contains(classMarker) && id.contains(methodMarker)).distinct().toList();
        require(selected.size() == 1, "expected exactly one distinct native UID, found " + selected.size());
        Files.writeString(selector.resolve("junit-platform-unique-ids-g3z.txt"), selected.get(0) + "\n");
    }

    private static Execution execute(List<String> command, Path report, Mode mode) throws IOException {
        Path stdout = report.resolve("stdout.log");
        Path stderr = report.resolve("stderr.log");
        Path sudo = trustedTool("sudo");
        Path timeout = trustedTool("timeout");
        Path unshare = trustedTool("unshare");
        Path setpriv = trustedTool("setpriv");
        List<String> wrapped = new ArrayList<>();
        wrapped.add(sudo.toString());
        wrapped.add("-n");
        wrapped.add(timeout.toString());
        wrapped.add("--signal=TERM");
        wrapped.add("--kill-after=" + mode.killGraceSeconds + "s");
        wrapped.add(mode.deadlineSeconds + "s");
        wrapped.add(unshare.toString());
        wrapped.add("--pid");
        wrapped.add("--fork");
        wrapped.add("--mount-proc");
        wrapped.add("--net");
        wrapped.add("--kill-child=KILL");
        wrapped.add("--");
        wrapped.add(setpriv.toString());
        String uid = numericIdentity("-u");
        String gid = numericIdentity("-g");
        wrapped.add("--reuid");
        wrapped.add(uid);
        wrapped.add("--regid");
        wrapped.add(gid);
        wrapped.add("--init-groups");
        wrapped.add("--pdeathsig");
        wrapped.add("keep");
        wrapped.add("--");
        wrapped.addAll(command);
        Files.writeString(report.resolve("command.argv"), String.join("\n", wrapped) + "\n");
        ProcessBuilder builder = new ProcessBuilder(wrapped)
                .directory(report.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("HOME", report.resolve("home").toString());
        environment.put("TMPDIR", report.resolve("tmp").toString());
        environment.put("TMP", report.resolve("tmp").toString());
        environment.put("TEMP", report.resolve("tmp").toString());
        Process process = builder.start();
        ProcessHandle root = process.toHandle();
        Map<Long, Identity> identities = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        boolean interrupted = false;
        Integer exitCode = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(hostDeadlineSeconds(mode)).toNanos();
        while (System.nanoTime() < deadline) {
            capture(root, identities, failures);
            try {
                if (process.waitFor(remainingMillis(deadline), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
            } catch (InterruptedException interruption) {
                interrupted = true;
                Thread.interrupted();
            }
        }
        if (exitCode == null) failures.add("namespace wrapper exceeded host deadline");
        if (exitCode != null) {
            capture(root, identities, failures);
            long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(CLEANUP_SECONDS).toNanos();
            interrupted |= confirmStopped(identities, cleanupDeadline, failures);
        }
        if (mode == Mode.FORCED && identities.keySet().stream().noneMatch(pid -> pid != root.pid())) {
            failures.add("forced mode captured no descendant identity");
        }
        if (interrupted) {
            failures.add("runner wait was interrupted");
            Thread.currentThread().interrupt();
        }
        return new Execution(exitCode, identities, identities.keySet().stream().anyMatch(pid -> pid != root.pid()),
                failures.stream().distinct().toList());
    }

    private static long remainingMillis(long deadline) {
        return Math.max(1, Math.min(POLL_MILLIS, Duration.ofNanos(Math.max(1, deadline - System.nanoTime())).toMillis()));
    }

    private static void capture(ProcessHandle root, Map<Long, Identity> identities, List<String> failures) {
        List<ProcessHandle> handles = new ArrayList<>();
        handles.add(root);
        try {
            handles.addAll(root.descendants().toList());
        } catch (RuntimeException failure) {
            if (safeAlive(root) == Boolean.TRUE) addOnce(failures, "could not enumerate live namespace descendants: " + failure);
        }
        for (ProcessHandle handle : handles) {
            Boolean alive = safeAlive(handle);
            if (alive == null) {
                addOnce(failures, "unknown liveness for PID " + handle.pid());
                continue;
            }
            if (!alive) continue;
            Long ticks = startTicks(handle);
            if (ticks == null) {
                if (safeAlive(handle) == Boolean.TRUE) addOnce(failures, "missing identity for live PID " + handle.pid());
                continue;
            }
            Identity previous = identities.get(handle.pid());
            if (previous == null) {
                identities.putIfAbsent(handle.pid(), new Identity(handle.pid(), ticks, handle));
            } else {
                observeIdentity(previous, ticks, alive, failures);
            }
        }
    }

    private static boolean confirmStopped(Map<Long, Identity> identities, long deadline, List<String> failures) {
        boolean interrupted = false;
        while (System.nanoTime() < deadline) {
            boolean stopped = true;
            for (Identity identity : identities.values()) {
                Boolean alive = safeAlive(identity.handle());
                if (alive == null) {
                    stopped = false;
                    addOnce(failures, "unknown liveness for PID " + identity.pid());
                } else if (alive) {
                    stopped = false;
                    Long ticks = startTicks(identity.handle());
                    if (ticks == null) addOnce(failures, "missing stat for live PID " + identity.pid());
                    else observeIdentity(identity, ticks, true, failures);
                }
            }
            if (stopped) return interrupted;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interruption) {
                Thread.interrupted();
                interrupted = true;
                failures.add("cleanup observation was interrupted");
            }
        }
        for (Identity identity : identities.values()) {
            if (safeAlive(identity.handle()) != Boolean.FALSE) failures.add("captured PID remained alive: " + identity.pid());
        }
        return interrupted;
    }

    private static Boolean safeAlive(ProcessHandle handle) {
        try {
            return handle.isAlive();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Long startTicks(ProcessHandle handle) {
        Path stat = Path.of("/proc", Long.toString(handle.pid()), "stat");
        try {
            String raw = Files.readString(stat);
            int close = raw.lastIndexOf(')');
            if (close <= 0) return null;
            String[] fields = raw.substring(close + 1).trim().split("\\s+");
            return fields.length > 19 ? Long.valueOf(fields[19]) : null;
        } catch (Exception failure) {
            return null;
        }
    }

    private static void validateNative(Path stdout, Path xmlDir) throws Exception {
        require(Files.isRegularFile(stdout), "native stdout report is missing");
        String output = Files.readString(stdout);
        require(output.contains("JUnit Platform on Native Image - report"), "native launcher header is missing");
        NativeSummary summary = readSummary(output);
        require(summary.testsFound() == 1 && summary.testsStarted() == 1 && summary.testsSucceeded() == 1,
                "native test summary does not report exactly one successful test");
        require(summary.testsAborted() == 0 && summary.testsFailed() == 0 && summary.testsSkipped() == 0,
                "native test summary reports skipped, aborted, or failed tests");
        require(summary.containersFound() > 0 && summary.containersStarted() == summary.containersFound()
                && summary.containersSuccessful() == summary.containersFound()
                && summary.containersSkipped() == 0 && summary.containersAborted() == 0 && summary.containersFailed() == 0,
                "native container summary is incomplete or failed");
        List<Path> xml;
        try (var files = Files.walk(xmlDir)) {
            xml = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml")).sorted().toList();
        }
        require(xml.size() == 1, "expected exactly one fresh native XML, found " + xml.size());
        validateXml(xml.get(0));
    }

    private static NativeSummary readSummary(String output) {
        List<String> lines = output.lines().toList();
        return new NativeSummary(summaryCount(lines, "containers found"), summaryCount(lines, "containers skipped"),
                summaryCount(lines, "containers started"), summaryCount(lines, "containers aborted"),
                summaryCount(lines, "containers successful"), summaryCount(lines, "containers failed"),
                summaryCount(lines, "tests found"), summaryCount(lines, "tests started"),
                summaryCount(lines, "tests successful"), summaryCount(lines, "tests aborted"),
                summaryCount(lines, "tests failed"), summaryCount(lines, "tests skipped"));
    }

    private static long summaryCount(List<String> lines, String label) {
        Pattern pattern = Pattern.compile("^\\[\\s*(\\d+)\\s+" + Pattern.quote(label) + "\\s*]$");
        List<String> matches = lines.stream().map(pattern::matcher).filter(java.util.regex.Matcher::matches)
                .map(match -> match.group(1)).toList();
        require(matches.size() == 1, "native summary must contain one '" + label + "' line, found " + matches.size());
        return Long.parseLong(matches.get(0));
    }

    private static void validateXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(QUIET_XML_ERRORS);
        Document document = builder.parse(path.toFile());
        Element suite = document.getDocumentElement();
        require(suite != null && suite.getTagName().equals("testsuite"), "native XML root is not testsuite");
        require(number(suite, "tests") == 1 && number(suite, "failures") == 0 && number(suite, "errors") == 0
                && number(suite, "skipped") == 0, "native XML suite counts are not exact");
        NodeList tests = suite.getElementsByTagName("testcase");
        require(tests.getLength() == 1, "native XML does not contain exactly one testcase");
        Element testcase = (Element) tests.item(0);
        require(TEST_CLASS.equals(testcase.getAttribute("classname")) && TESTCASE_NAME.equals(testcase.getAttribute("name")),
                "native XML testcase identity is wrong");
        require(testcase.getElementsByTagName("failure").getLength() == 0
                && testcase.getElementsByTagName("error").getLength() == 0
                && testcase.getElementsByTagName("skipped").getLength() == 0
                && testcase.getElementsByTagName("aborted").getLength() == 0,
                "native XML testcase has failure, error, skipped, or aborted nodes");
    }

    private static long number(Element element, String name) {
        String value = element.getAttribute(name);
        require(!value.isBlank(), "native XML is missing " + name);
        return Long.parseLong(value);
    }

    private static void validateForced(Path evidence, Execution execution) throws IOException {
        require(execution.exitCode() != null && Set.of(124, 137, 143).contains(execution.exitCode()),
                "forced mode must end with timeout status 124, 137, or 143");
        Map<String, String> started = keyValues(evidence.resolve("forced-started.txt"));
        require(Long.parseLong(started.getOrDefault("pid", "0")) > 0 && "true".equals(started.get("pid1")),
                "forced control did not report a positive PID1 readiness");
        require(Files.isRegularFile(evidence.resolve("forced-term-observed.txt"))
                && Files.readString(evidence.resolve("forced-term-observed.txt")).trim().equals("true"),
                "forced control did not observe TERM");
        require(execution.descendantCaptured(), "forced mode captured no non-wrapper descendant");
    }

    private static Map<String, String> keyValues(Path path) throws IOException {
        require(Files.isRegularFile(path), "missing key-value report: " + path);
        Map<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            int equals = line.indexOf('=');
            require(equals > 0 && result.put(line.substring(0, equals), line.substring(equals + 1)) == null,
                    "malformed or duplicate key-value report: " + path);
        }
        return result;
    }

    private static void deleteOwned(Path report, Path... paths) throws IOException {
        Path normalizedReport = report.toAbsolutePath().normalize();
        for (Path path : paths) {
            if (path == null) continue;
            Path normalized = path.toAbsolutePath().normalize();
            require(
                    normalized.startsWith(normalizedReport) && !normalized.equals(normalizedReport),
                    "refusing cleanup outside run directory");
            deleteTree(normalized);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makePrivate(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows/macOS filesystems without POSIX permissions still use fresh private paths.
        }
    }

    private static void writeSummary(Path report, Mode mode, Execution execution, Throwable failure) {
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("mode=").append(mode.name).append('\n');
            summary.append("exit=").append(execution == null || execution.exitCode() == null
                    ? "unavailable" : execution.exitCode()).append('\n');
            summary.append("captured-identities=").append(execution == null ? 0 : execution.identities().size()).append('\n');
            if (execution != null) {
                for (String observation : execution.failures()) summary.append("observation=").append(observation).append('\n');
            }
            if (failure != null) summary.append("failure=").append(failure).append('\n');
            Files.writeString(report.resolve("summary.txt"), summary);
        } catch (IOException ignored) {
            // Preserve the original gate failure; stdout/stderr remain the primary diagnostics.
        }
    }

    private static void addOnce(List<String> failures, String message) {
        if (!failures.contains(message)) failures.add(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void selfTest() throws Exception {
        Path root = Files.createTempDirectory("g3z-runner-self-test-");
        try {
            expectFailure(() -> parse(new String[] {"--unknown", "value"}), IllegalArgumentException.class,
                    "unknown or misplaced argument");
            expectFailure(() -> parse(new String[] {"--mode", "invalid"}), IllegalArgumentException.class, "--mode must be");
            expectFailure(() -> parse(new String[] {"--mode", "jvm"}), IllegalArgumentException.class, "missing or blank --report-dir");
            Path xml = root.resolve("xml");
            Files.createDirectories(xml);
            Files.writeString(xml.resolve("one.xml"), validXml());
            Files.writeString(root.resolve("stdout.log"), validSummary());
            validateNative(root.resolve("stdout.log"), xml);
            expectXmlFailure(root.resolve("wrong.xml"), validXml().replace(TESTCASE_NAME, "wrong()"), "testcase identity is wrong");
            expectXmlFailure(root.resolve("zero.xml"), validXml().replace("tests=\"1\"", "tests=\"0\""), "suite counts are not exact");
            expectXmlFailure(
                    root.resolve("skipped.xml"), validXml().replace("</testcase>", "<skipped/></testcase>"),
                    "testcase has failure");
            expectXmlFailure(
                    root.resolve("aborted.xml"), validXml().replace("</testcase>", "<aborted/></testcase>"),
                    "testcase has failure");
            Files.writeString(
                    root.resolve("failed-container.log"),
                    validSummary().replace("[ 0 containers failed ]", "[ 1 containers failed ]"));
            expectFailure(
                    () -> validateNative(root.resolve("failed-container.log"), xml),
                    "native container summary is incomplete or failed");
            Files.writeString(root.resolve("duplicate.log"), validSummary() + "[ 1 tests found ]\n");
            expectFailure(() -> readSummary(Files.readString(root.resolve("duplicate.log"))),
                    "native summary must contain one 'tests found' line");
            expectXmlFailure(root.resolve("malformed.xml"), "<testsuite", SAXParseException.class);
            expectXmlFailure(root.resolve("dtd.xml"), "<!DOCTYPE testsuite>" + validXml(), SAXParseException.class);
            Path multiple = root.resolve("multiple");
            Files.createDirectories(multiple);
            Files.writeString(multiple.resolve("a.xml"), validXml());
            Files.writeString(multiple.resolve("b.xml"), validXml());
            expectFailure(() -> validateNative(root.resolve("stdout.log"), multiple), "expected exactly one fresh native XML");
            Files.createDirectories(root.resolve("empty"));
            expectFailure(() -> validateNative(root.resolve("stdout.log"), root.resolve("empty")), "expected exactly one fresh native XML");
            Path ids = root.resolve("ids");
            Files.createDirectories(ids);
            Files.writeString(
                    ids.resolve("junit-platform-unique-ids-a.txt"),
                    "[engine:junit]\n[class:" + TEST_CLASS + "][method:" + TEST_METHOD + "()]\n");
            Path selected = root.resolve("selected");
            Files.createDirectories(selected);
            selectUid(ids, selected);
            Files.writeString(
                    ids.resolve("junit-platform-unique-ids-b.txt"),
                    "[engine:other][class:" + TEST_CLASS + "][method:" + TEST_METHOD + "()]\n");
            expectFailure(() -> selectUid(ids, selected), "expected exactly one distinct native UID");
            deleteTree(ids);
            Files.createDirectories(ids);
            expectFailure(() -> selectUid(ids, selected), "expected exactly one distinct native UID");
            Path forced = root.resolve("forced");
            Files.createDirectories(forced);
            Files.writeString(forced.resolve("forced-started.txt"), "pid=123\npid1=true\n");
            Files.writeString(forced.resolve("forced-term-observed.txt"), "true\n");
            expectFailure(() -> validateForced(forced, new Execution(0, Map.of(), false, List.of())),
                    "forced mode must end with timeout status");
            expectFailure(() -> validateForced(forced, new Execution(124, Map.of(), false, List.of())), "no non-wrapper descendant");
            require(hostDeadlineSeconds(Mode.JVM) == 145 && hostDeadlineSeconds(Mode.NATIVE) == 145
                    && hostDeadlineSeconds(Mode.FORCED) == 27, "deadline arithmetic changed");
            Identity current = new Identity(ProcessHandle.current().pid(), 1, ProcessHandle.current());
            expectFailure(() -> requireIdentity(current, 2, true), "process identity changed while live");
            Path external = root.resolve("external");
            Path stage = root.resolve("stage");
            Files.createDirectories(external);
            Files.createDirectories(stage);
            Files.writeString(external.resolve("keep"), "keep");
            boolean symlinkProbe = true;
            try {
                Files.createSymbolicLink(stage.resolve("external"), external);
            } catch (UnsupportedOperationException | SecurityException | java.nio.file.FileSystemException failure) {
                if ("Linux".equals(System.getProperty("os.name"))) throw failure;
                symlinkProbe = false;
            }
            if (symlinkProbe) {
                deleteTree(stage);
                require(Files.isRegularFile(external.resolve("keep")), "cleanup followed a symlink");
            } else {
                System.out.println("G3z self-test symlink probe skipped: capability unavailable");
            }
            System.out.println("GitChildProcessCleanup self-tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static long hostDeadlineSeconds(Mode mode) { return mode.deadlineSeconds + mode.killGraceSeconds + 15; }

    private static void observeIdentity(Identity identity, long actualTicks, boolean alive, List<String> failures) {
        try {
            requireIdentity(identity, actualTicks, alive);
        } catch (IllegalStateException failure) {
            addOnce(failures, failure.getMessage());
        }
    }

    private static void requireIdentity(Identity identity, long actualTicks, boolean alive) {
        require(!alive || identity.startTicks() == actualTicks, "process identity changed while live");
    }

    private static void expectXmlFailure(Path path, String contents, String expectedMessage) throws Exception {
        Files.writeString(path, contents);
        expectFailure(() -> validateXml(path), expectedMessage);
    }

    private static void expectXmlFailure(Path path, String contents, Class<? extends Throwable> expectedType) throws Exception {
        Files.writeString(path, contents);
        expectFailure(() -> validateXml(path), expectedType);
    }

    private static String validXml() {
        return "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\""
                + TEST_CLASS + "\" name=\"" + TESTCASE_NAME + "\"></testcase></testsuite>";
    }

    private static String validSummary() {
        return "JUnit Platform on Native Image - report\n"
                + "[ 1 containers found ]\n[ 0 containers skipped ]\n[ 1 containers started ]\n"
                + "[ 0 containers aborted ]\n[ 1 containers successful ]\n[ 0 containers failed ]\n"
                + "[ 1 tests found ]\n[ 1 tests started ]\n[ 1 tests successful ]\n"
                + "[ 0 tests aborted ]\n[ 0 tests failed ]\n[ 0 tests skipped ]\n";
    }

    private static void expectFailure(Throwing action, String expectedMessage) throws Exception {
        expectFailure(action, IllegalStateException.class, expectedMessage);
    }

    private static void expectFailure(Throwing action, Class<? extends Throwable> expectedType) throws Exception {
        expectFailure(action, expectedType, null);
    }

    private static void expectFailure(Throwing action, Class<? extends Throwable> expectedType, String expectedMessage)
            throws Exception {
        try {
            action.run();
        } catch (Throwable actual) {
            require(expectedType.isInstance(actual), expectedMessage + " threw " + actual.getClass().getName()
                    + "; expected " + expectedType.getName());
            if (expectedMessage != null) {
                require(actual.getMessage() != null && actual.getMessage().contains(expectedMessage),
                        "message did not contain '" + expectedMessage + "': " + actual.getMessage());
            }
            return;
        }
        throw new IllegalStateException("self-test did not reject " + expectedMessage);
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
