import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public final class JadxWebServer {
    private static final long MAX_UPLOAD = Long.parseLong(System.getenv().getOrDefault("MAX_UPLOAD_BYTES", "104857600"));
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final String JADX_BIN = System.getenv().getOrDefault("JADX_BIN", "build/jadx/bin/jadx");
    private static final Path STATIC_ROOT = Paths.get(System.getenv().getOrDefault("STATIC_ROOT", "jadx-web/web")).toAbsolutePath().normalize();
    private static final Path SESSION_ROOT = Paths.get(System.getenv().getOrDefault("SESSION_ROOT", System.getProperty("java.io.tmpdir")), "jadx-web-sessions");
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final Semaphore DECOMPILE_SLOTS = new Semaphore(Integer.parseInt(System.getenv().getOrDefault("MAX_CONCURRENT_DECOMPILES", "2")));
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(Long.parseLong(System.getenv().getOrDefault("SESSION_TTL_MINUTES", "30")));

    record Session(Path root, Path output, Instant createdAt, String originalName) {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(SESSION_ROOT);
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/health", ex -> json(ex, 200, "{\"ok\":true}"));
        server.createContext("/api/decompile", JadxWebServer::decompile);
        server.createContext("/api/session", JadxWebServer::sessionApi);
        server.createContext("/", JadxWebServer::staticFile);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(JadxWebServer::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);

        server.start();
        System.out.println("JADX Web listening on http://0.0.0.0:" + PORT);
    }

    private static void decompile(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            methodNotAllowed(ex);
            return;
        }
        String filename = sanitizeFilename(query(ex.getRequestURI()).getOrDefault("name", "upload.apk"));
        if (!supported(filename)) {
            json(ex, 415, "{\"error\":\"Unsupported file type\"}");
            return;
        }

        boolean acquired = false;
        String id = UUID.randomUUID().toString().replace("-", "");
        Path root = SESSION_ROOT.resolve(id);
        Path input = root.resolve("input").resolve(filename);
        Path output = root.resolve("output");
        try {
            acquired = DECOMPILE_SLOTS.tryAcquire(1, TimeUnit.SECONDS);
            if (!acquired) {
                json(ex, 429, "{\"error\":\"Server is busy. Try again shortly.\"}");
                return;
            }
            Files.createDirectories(input.getParent());
            Files.createDirectories(output);

            long written = copyLimited(ex.getRequestBody(), input, MAX_UPLOAD);
            if (written == 0) throw new IOException("Empty upload");

            ProcessBuilder pb = new ProcessBuilder(JADX_BIN, "--no-debug-info", "-d", output.toString(), input.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String log;
            try (InputStream in = p.getInputStream()) {
                log = new String(in.readNBytes(1_000_000), StandardCharsets.UTF_8);
            }
            boolean finished;
            try {
                finished = p.waitFor(180, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Decompilation interrupted", e);
            }
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("Decompilation timed out");
            }
            if (p.exitValue() != 0 && isDirectoryEmpty(output)) {
                throw new IOException("JADX failed: " + trimLog(log));
            }

            sessions.put(id, new Session(root, output, Instant.now(), filename));
            int files = countFiles(output);
            json(ex, 200, "{\"id\":\"" + id + "\",\"name\":\"" + esc(filename) + "\",\"files\":" + files + "}");
        } catch (Exception e) {
            deleteTree(root);
            json(ex, 500, "{\"error\":\"" + esc(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "\"}");
        } finally {
            if (acquired) DECOMPILE_SLOTS.release();
        }
    }

    private static void sessionApi(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            methodNotAllowed(ex);
            return;
        }
        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 5) {
            json(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        String id = parts[3];
        String action = parts[4];
        Session session = sessions.get(id);
        if (session == null) {
            json(ex, 404, "{\"error\":\"Session expired or not found\"}");
            return;
        }

        if ("tree".equals(action)) {
            List<String> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(session.output())) {
                walk.filter(Files::isRegularFile)
                        .map(session.output()::relativize)
                        .map(Path::toString)
                        .map(s -> s.replace(File.separatorChar, '/'))
                        .sorted()
                        .forEach(files::add);
            }
            StringBuilder sb = new StringBuilder("{\"name\":\"").append(esc(session.originalName())).append("\",\"files\":[");
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('\"').append(esc(files.get(i))).append('\"');
            }
            sb.append("]}");
            json(ex, 200, sb.toString());
            return;
        }

        if ("file".equals(action)) {
            String rel = query(ex.getRequestURI()).get("path");
            if (rel == null || rel.isBlank()) {
                json(ex, 400, "{\"error\":\"Missing path\"}");
                return;
            }
            Path file = session.output().resolve(rel).normalize();
            if (!file.startsWith(session.output()) || !Files.isRegularFile(file)) {
                json(ex, 404, "{\"error\":\"File not found\"}");
                return;
            }
            long size = Files.size(file);
            if (size > 2_000_000) {
                json(ex, 413, "{\"error\":\"File is too large for browser preview\"}");
                return;
            }
            byte[] data = Files.readAllBytes(file);
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", "text/plain; charset=utf-8");
            h.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            return;
        }

        json(ex, 404, "{\"error\":\"Not found\"}");
    }

    private static void staticFile(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            methodNotAllowed(ex);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = STATIC_ROOT.resolve(path.substring(1)).normalize();
        if (!file.startsWith(STATIC_ROOT) || !Files.isRegularFile(file)) {
            file = STATIC_ROOT.resolve("index.html");
        }
        byte[] data = Files.readAllBytes(file);
        String type = contentType(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", file.getFileName().toString().equals("index.html") ? "no-cache" : "public, max-age=3600");
        ex.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(ex.getRequestMethod()) ? -1 : data.length);
        if (!"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        } else {
            ex.close();
        }
    }

    private static long copyLimited(InputStream in, Path dest, long max) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new IOException("Upload exceeds " + (max / 1024 / 1024) + " MB limit");
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) return map;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String sanitizeFilename(String name) {
        String clean = Paths.get(name).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isBlank() ? "upload.apk" : clean;
    }

    private static boolean supported(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".apk") || n.endsWith(".dex") || n.endsWith(".aab") || n.endsWith(".jar")
                || n.endsWith(".zip") || n.endsWith(".aar") || n.endsWith(".xapk") || n.endsWith(".apkm");
    }

    private static int countFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return (int) Math.min(Integer.MAX_VALUE, s.filter(Files::isRegularFile).count());
        }
    }

    private static boolean isDirectoryEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.findAny().isEmpty(); }
    }

    private static String trimLog(String log) {
        String clean = log == null ? "" : log.replaceAll("\\s+", " ").trim();
        return clean.length() > 500 ? clean.substring(0, 500) + "…" : clean;
    }

    private static void cleanupExpiredSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_TTL_MS;
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().createdAt().toEpochMilli() < cutoff) {
                deleteTree(e.getValue().root());
                return true;
            }
            return false;
        });
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static void methodNotAllowed(HttpExchange ex) throws IOException {
        json(ex, 405, "{\"error\":\"Method not allowed\"}");
    }

    private static String contentType(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
