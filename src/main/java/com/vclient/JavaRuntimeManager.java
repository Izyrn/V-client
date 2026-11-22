package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class JavaRuntimeManager {
    private static final String AZUL_API = "https://api.azul.com/metadata/v1/zulu/packages/";
    private static final String AZUL_DOWNLOAD_HOST = "cdn.azul.com";
    private static final long MAX_ARCHIVE_BYTES = 150L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Path DATA_DIRECTORY = AppInfo.dataDir();
    private static final java.util.Set<Integer> SUPPORTED_JAVA_MAJORS = java.util.Set.of(8, 17, 21, 25);

    Path resolve(int requiredMajor, Consumer<String> onOutput, boolean allowDownloads) throws Exception {
        if (!SUPPORTED_JAVA_MAJORS.contains(requiredMajor)) {
            throw new IOException("Minecraft requires Java " + requiredMajor
                    + "; VYRN supports 64-bit Azul Zulu Java 8, 17, 21, and 25.");
        }
        onOutput.accept("Checking for installed 64-bit Java " + requiredMajor + " runtimes.");
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                || !System.getProperty("os.arch").contains("64")) {
            throw new IOException("VYRN requires the 64-bit Windows Azul Zulu runtime for Minecraft.");
        }
        Path cached = DATA_DIRECTORY.resolve("runtimes")
                .resolve("zulu-" + requiredMajor + "-windows-x64");
        Path java = findJavaExecutable(cached);
        if (java != null && isSupportedRuntime(java, requiredMajor)) {
            onOutput.accept("Using VYRN's installed Java " + requiredMajor + " runtime: " + java);
            return java;
        }

        if (requiredMajor == 25) {
            java = detectInstalledJava25();
            if (java != null) {
                onOutput.accept("Using detected installed Java 25 runtime: " + java);
                return java;
            }
        }

        if (requiredMajor == 21) {
            Path bundledRuntime = bundledRuntimeHome();
            if (bundledRuntime != null) {
                java = findJavaExecutable(bundledRuntime);
                if (java != null && isSupportedRuntime(java, requiredMajor)) {
                    onOutput.accept("Using bundled Java " + requiredMajor + " runtime: " + java);
                    return java;
                }
            }
        }
        if (!allowDownloads) {
            throw new IOException("This profile needs Azul Zulu Java " + requiredMajor
                    + ", but no compatible runtime is installed locally. This launch cannot download runtimes; "
                    + "connect to the internet and use the regular Play button to install the required runtime.");
        }
        return install(requiredMajor, cached, onOutput);
    }

    static String selectDownloadUrl(JsonNode packages, int requiredMajor) throws IOException {
        return java.util.stream.StreamSupport.stream(packages.spliterator(), false)
                .filter(item -> item.path("product").asText().equalsIgnoreCase("zulu"))
                .filter(item -> item.path("java_version").isArray()
                        && item.path("java_version").path(0).asInt(-1) == requiredMajor)
                .filter(item -> item.path("name").asText().endsWith("-win_x64.zip"))
                .filter(item -> item.path("name").asText().contains("-ca-jre")
                        && !item.path("name").asText().contains("-fx-"))
                .map(item -> item.path("download_url").asText(""))
                .filter(url -> {
                    try {
                        URI uri = URI.create(url);
                        return "https".equalsIgnoreCase(uri.getScheme())
                                && AZUL_DOWNLOAD_HOST.equalsIgnoreCase(uri.getHost());
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Azul did not offer a verified 64-bit Windows Zulu JRE for Java " + requiredMajor + "."));
    }

    private Path install(int requiredMajor, Path destination, Consumer<String> onOutput) throws Exception {
        if (!SUPPORTED_JAVA_MAJORS.contains(requiredMajor)) {
            throw new IOException("Minecraft requires Java " + requiredMajor
                    + "; VYRN supports 64-bit Azul Zulu Java 8, 17, 21, and 25.");
        }
        onOutput.accept("Preparing the 64-bit Azul Zulu Java " + requiredMajor + " runtime...");
        String query = "java_version=" + requiredMajor
                + "&os=windows&arch=x86&hw_bitness=64&java_package_type=jre&archive_type=zip"
                + "&release_status=ga&availability_types=CA&latest=true&bundle_type=jre";
        URI metadataUri = URI.create(AZUL_API + "?" + query);
        HttpRequest metadataRequest = HttpRequest.newBuilder(metadataUri)
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build();
        JsonNode packages;
        try {
            packages = readJson(PlatformTls.sendWithRetries(HTTP, metadataRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), onOutput), metadataUri);
        } catch (IOException e) {
            throw explainTlsFailure(metadataUri.toString(), e);
        }
        String downloadUrl = selectDownloadUrl(packages, requiredMajor);

        Path dataDirectory = destination.getParent().getParent();
        Files.createDirectories(dataDirectory);
        Path archive = Files.createTempFile(dataDirectory, "zulu-" + requiredMajor + "-", ".zip");
        Path extraction = Files.createTempDirectory(dataDirectory, "zulu-" + requiredMajor + "-");
        Path stagedRuntime = dataDirectory.resolve("zulu-" + requiredMajor + "-stage");
        try {
            onOutput.accept("Downloading the official Azul Zulu Java " + requiredMajor + " x64 runtime...");
            HttpRequest downloadRequest = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<Path> response;
            try {
                response = PlatformTls.sendWithRetries(HTTP, downloadRequest,
                        HttpResponse.BodyHandlers.ofFile(archive), onOutput);
            } catch (IOException e) {
                throw explainTlsFailure(downloadUrl, e);
            }
            if (!AZUL_DOWNLOAD_HOST.equalsIgnoreCase(response.uri().getHost())) {
                throw new IOException("The Azul runtime download redirected to an unexpected host.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Azul Zulu download failed with HTTP " + response.statusCode() + ".");
            }
            long archiveSize = Files.size(archive);
            if (archiveSize == 0 || archiveSize > MAX_ARCHIVE_BYTES) {
                throw new IOException("Azul returned a Zulu JRE archive with an invalid size.");
            }
            extractRuntime(archive, extraction);
            Path java = findJavaExecutable(extraction);
            if (java == null || !isZuluRuntime(java, requiredMajor)) {
                throw new IOException("The downloaded Azul package is not a 64-bit Zulu Java "
                        + requiredMajor + " runtime.");
            }

            deleteTree(stagedRuntime);
            Files.createDirectories(stagedRuntime);
            Path runtimeHome = java.getParent().getParent();
            try (var files = Files.walk(runtimeHome)) {
                for (Path source : files.toList()) {
                    Path target = stagedRuntime.resolve(runtimeHome.relativize(source));
                    if (Files.isDirectory(source)) Files.createDirectories(target);
                    else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            deleteTree(destination);
            Files.createDirectories(destination.getParent());
            try {
                Files.move(stagedRuntime, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stagedRuntime, destination);
            }
            java = destination.resolve("bin").resolve("java.exe");
            if (!isZuluRuntime(java, requiredMajor)) {
                deleteTree(destination);
                throw new IOException("The installed Azul Zulu Java runtime failed its architecture or version check.");
            }
            onOutput.accept("Azul Zulu Java " + requiredMajor + " x64 is ready.");
            return java;
        } finally {
            Files.deleteIfExists(archive);
            deleteTree(extraction);
            deleteTree(stagedRuntime);
        }
    }

    private static void extractRuntime(Path archive, Path destination) throws IOException {
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
                    throw new IOException("Azul's JRE archive contains an unsafe path.");
                }
                Path relative = Path.of(name).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) {
                    throw new IOException("Azul's JRE archive contains an unsafe path.");
                }
                Path output = destination.resolve(relative).normalize();
                if (!output.startsWith(destination)) {
                    throw new IOException("Azul's JRE archive tried to write outside its install directory.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (var file = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_EXTRACTED_BYTES) {
                            throw new IOException("The downloaded Azul Zulu JRE exceeds the extraction size limit.");
                        }
                        file.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static boolean isZuluRuntime(Path executable, int requiredMajor) {
        return isRuntime(executable, requiredMajor, true);
    }

    private static boolean isSupportedRuntime(Path executable, int requiredMajor) {
        return isRuntime(executable, requiredMajor, requiredMajor != 25);
    }

    private static boolean isRuntime(Path executable, int requiredMajor, boolean requireZulu) {
        if (!Files.isRegularFile(executable)) return false;
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0
                    && output.contains("os.arch = amd64")
                    && (output.contains("java.specification.version = " + requiredMajor)
                    || requiredMajor == 8 && output.contains("java.specification.version = 1.8"))
                    && (!requireZulu || output.toLowerCase(java.util.Locale.ROOT).contains("azul"));
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static Path detectInstalledJava25() throws IOException {
        java.util.LinkedHashSet<Path> candidates = new java.util.LinkedHashSet<>();
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(Path.of(javaHome).resolve("bin").resolve("java.exe"));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(
                    System.getProperty("path.separator")))) {
                if (!directory.isBlank()) candidates.add(Path.of(directory).resolve("java.exe"));
            }
        }
        String programFiles = System.getenv("ProgramFiles");
        String localPrograms = System.getenv("LOCALAPPDATA");
        addJava25InstallCandidates(candidates, programFiles == null ? null : Path.of(programFiles));
        addJava25InstallCandidates(candidates, localPrograms == null ? null
                : Path.of(localPrograms).resolve("Programs"));
        for (Path candidate : candidates) {
            if (isRuntime(candidate, 25, false)) return candidate;
        }
        return null;
    }

    static String describeRuntime(Path executable) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "-version")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Java version check timed out for " + executable);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Java version check failed for " + executable + ": " + output);
        }
        return output.isBlank() ? "Java version command returned no details." : output;
    }

    private static void addJava25InstallCandidates(java.util.Set<Path> candidates, Path programsDirectory)
            throws IOException {
        if (programsDirectory == null) return;
        for (String vendor : java.util.List.of("Java", "Zulu", "Eclipse Adoptium", "Microsoft")) {
            Path vendorDirectory = programsDirectory.resolve(vendor);
            if (!Files.isDirectory(vendorDirectory)) continue;
            try (var installations = Files.list(vendorDirectory)) {
                installations.filter(Files::isDirectory)
                        .map(directory -> directory.resolve("bin").resolve("java.exe"))
                        .forEach(candidates::add);
            }
        }
    }

    private static Path bundledRuntimeHome() throws java.net.URISyntaxException {
        Path codeSource = Path.of(JavaRuntimeManager.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Path appDirectory = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
        if (appDirectory == null) return null;
        Path bundle = appDirectory.getParent();
        if (bundle == null) return null;
        // Older releases shipped a second runtime for Minecraft; newer ones reuse the launcher's own.
        Path separateRuntime = bundle.resolve("runtimes").resolve("zulu21");
        return Files.isDirectory(separateRuntime) ? separateRuntime : bundle.resolve("runtime");
    }

    private static Path findJavaExecutable(Path root) throws IOException {
        if (!Files.isDirectory(root)) return null;
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equalsIgnoreCase("java.exe"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .findFirst().orElse(null);
        }
    }

    private static JsonNode readJson(HttpResponse<String> response, URI endpoint) throws IOException {
        if (!"api.azul.com".equalsIgnoreCase(response.uri().getHost())) {
            throw new IOException("Azul runtime metadata redirected to an unexpected host.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Official runtime metadata server returned HTTP "
                    + response.statusCode() + " for " + endpoint.getHost() + ".");
        }
        try {
            JsonNode json = JSON.readTree(response.body());
            if (json == null || !json.isArray()) throw new IOException("The Azul metadata response is empty.");
            return json;
        } catch (IOException e) {
            throw new IOException("Azul returned invalid Zulu runtime metadata.", e);
        }
    }

    static IOException explainTlsFailure(String address, IOException error) {
        return explainTlsFailure(address, error, DnsBlock::inspect);
    }

    /** {@code dnsCheck} looks a host up; it is a parameter so tests do not depend on the network they run on. */
    static IOException explainTlsFailure(String address, IOException error,
                                         java.util.function.Function<String, DnsBlock.Finding> dnsCheck) {
        String host = null;
        try {
            host = URI.create(address).getHost();
        } catch (IllegalArgumentException ignored) {
            // Not a URL; fall through to the generic messages.
        }
        if (host != null) {
            DnsBlock.Finding blocked = dnsCheck.apply(host);
            if (blocked != null) {
                return new IOException(DnsBlock.explain(blocked) + " (Technical detail: "
                        + PlatformTls.describeFailure(error) + ")", error);
            }
        }
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof javax.net.ssl.SSLException) {
                return new IOException("Secure connection to " + address
                        + " failed during the TLS handshake. " + PlatformTls.describeFailure(error)
                        + ". Selected route: " + PlatformTls.proxyDescription(URI.create(address))
                        + ". VYRN validates certificates against the Windows and Java trust stores "
                        + "and will not disable certificate validation.", error);
            }
            cause = cause.getCause();
        }
        if (error instanceof ConnectException || error instanceof java.net.http.HttpConnectTimeoutException) {
            return new IOException("Could not connect securely to " + address
                    + ". " + PlatformTls.describeFailure(error) + ". Selected route: "
                    + PlatformTls.proxyDescription(URI.create(address))
                    + ". Check your internet connection, proxy, and firewall.", error);
        }
        return new IOException("Request to " + address + " failed: "
                + PlatformTls.describeFailure(error) + ". Selected route: "
                + PlatformTls.proxyDescription(URI.create(address)) + ".", error);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
