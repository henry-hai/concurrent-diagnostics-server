package io.github.henryhai.diagnostics;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP server over TCP: static files under a document root, GET/HEAD, pooled threads per connection. */
public final class WebServer implements Runnable {

    private static final String DEFAULT_FILE = "index.html";
    private static final String FILE_NOT_FOUND = "404.html";
    private static final String METHOD_NOT_SUPPORTED = "not_supported.html";
    private static final String SERVER_BANNER = "ConcurrentDiagnosticsServer/1.0 (Java)";

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private final Path webRoot;
    private final Socket connect;

    private WebServer(Path webRoot, Socket connect) {
        this.webRoot = webRoot;
        this.connect = connect;
    }

    public static void main(String[] args) {
        int port = parsePort(args);
        Path root = resolveWebRoot();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.printf("Listening on http://localhost:%d (document root: %s)%n", port, root.toAbsolutePath());

            while (true) {
                Socket client = serverSocket.accept();
                EXECUTOR.submit(new WebServer(root, client));
            }
        } catch (IOException e) {
            System.err.println("Server failed: " + e.getMessage());
        }
    }

    private static int parsePort(String[] args) {
        if (args.length > 0) {
            try {
                int p = Integer.parseInt(args[0]);
                if (p > 0 && p <= 65535) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
            }
            System.err.println("Invalid port; using default.");
        }
        String env = System.getenv("DIAGNOSTICS_PORT");
        if (env != null && !env.isBlank()) {
            try {
                int p = Integer.parseInt(env.trim());
                if (p > 0 && p <= 65535) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 8080;
    }

    private static Path resolveWebRoot() {
        String override = System.getenv("DIAGNOSTICS_WEB_ROOT");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath().normalize();
        }
        return Path.of("public").toAbsolutePath().normalize();
    }

    @Override
    public void run() {
        try (Socket socket = connect) {
            handleClient(socket);
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             BufferedOutputStream dataOut = new BufferedOutputStream(socket.getOutputStream())) {
            PrintWriter out = new PrintWriter(new OutputStreamWriter(dataOut, StandardCharsets.US_ASCII), false);

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }

            StringTokenizer parse = new StringTokenizer(requestLine);
            if (parse.countTokens() < 2) {
                return;
            }

            String method = parse.nextToken().toUpperCase(Locale.ROOT);
            String rawPath = parse.nextToken();

            if (!isPathSafe(rawPath)) {
                sendPlainText(out, dataOut, 400, "Bad Request");
                return;
            }

            String path = normalizePath(rawPath);

            if ("GET".equals(method) || "HEAD".equals(method)) {
                if (isDiagnosticsPath(path)) {
                    sendDiagnostics(out, dataOut, method);
                    return;
                }
                serveStatic(out, dataOut, method, path);
            } else {
                sendErrorPage(out, dataOut, 501, METHOD_NOT_SUPPORTED, "text/html");
            }
        }
    }

    private static boolean isPathSafe(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return !path.contains("..") && !path.contains("\0");
    }

    private static String normalizePath(String rawPath) {
        String p = rawPath;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty() || p.endsWith("/")) {
            if (p.endsWith("/")) {
                return p + DEFAULT_FILE;
            }
            return DEFAULT_FILE;
        }
        return p;
    }

    private static boolean isDiagnosticsPath(String normalizedPath) {
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        return "diagnostics".equals(lower)
                || "diagnostics.txt".equals(lower)
                || "api/diagnostics".equals(lower);
    }

    private void sendDiagnostics(PrintWriter out, BufferedOutputStream dataOut, String method) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long free = rt.freeMemory();
        long total = rt.totalMemory();
        long max = rt.maxMemory();
        String body = String.format(Locale.US,
                "Concurrent Diagnostics Server%n"
                        + "timestamp: %tc%n"
                        + "processors: %d%n"
                        + "heap: used=%d KB committed=%d KB max=%d KB%n",
                new Date(),
                rt.availableProcessors(),
                (total - free) / 1024,
                total / 1024,
                max == Long.MAX_VALUE ? -1 : max / 1024);

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, 200, "text/plain; charset=utf-8", payload.length);
        out.flush();
        if ("GET".equals(method)) {
            dataOut.write(payload);
            dataOut.flush();
        }
    }

    private void serveStatic(PrintWriter out, BufferedOutputStream dataOut, String method, String relativePath)
            throws IOException {
        Path filePath = webRoot.resolve(relativePath).normalize();
        if (!filePath.startsWith(webRoot)) {
            sendPlainText(out, dataOut, 403, "Forbidden");
            return;
        }

        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            sendErrorPage(out, dataOut, 404, FILE_NOT_FOUND, "text/html");
            return;
        }

        long lenLong = file.length();
        if (lenLong > Integer.MAX_VALUE) {
            sendPlainText(out, dataOut, 413, "Payload Too Large");
            return;
        }
        int fileLength = (int) lenLong;
        String contentType = contentTypeFor(relativePath);

        byte[] fileData = "GET".equals(method) ? readFileBytes(file, fileLength) : null;

        writeHeaders(out, 200, contentType, fileLength);
        out.flush();
        if ("GET".equals(method) && fileData != null) {
            dataOut.write(fileData, 0, fileLength);
            dataOut.flush();
        }
    }

    private void sendErrorPage(PrintWriter out, BufferedOutputStream dataOut, int status, String pageName, String mime)
            throws IOException {
        Path page = webRoot.resolve(pageName).normalize();
        if (!page.startsWith(webRoot) || !Files.isRegularFile(page)) {
            sendPlainText(out, dataOut, status, status == 404 ? "Not Found" : "Error");
            return;
        }

        int length = (int) Files.size(page);
        byte[] data = readFileBytes(page.toFile(), length);
        writeHeaders(out, status, mime, length);
        out.flush();
        dataOut.write(data, 0, length);
        dataOut.flush();
    }

    private void sendPlainText(PrintWriter out, BufferedOutputStream dataOut, int status, String message)
            throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, status, "text/plain; charset=utf-8", payload.length);
        out.flush();
        dataOut.write(payload);
        dataOut.flush();
    }

    private void writeHeaders(PrintWriter out, int status, String contentType, int contentLength) {
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 413 -> "Payload Too Large";
            case 501 -> "Not Implemented";
            default -> "Error";
        };
        out.printf("HTTP/1.1 %d %s%n", status, reason);
        out.println("Server: " + SERVER_BANNER);
        out.println("Date: " + new Date());
        out.println("Connection: close");
        out.println("Content-type: " + contentType);
        out.println("Content-length: " + contentLength);
        out.println();
    }

    private static byte[] readFileBytes(File file, int length) throws IOException {
        byte[] data = new byte[length];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = in.readNBytes(data, 0, length);
            if (read != length) {
                throw new IOException("Unexpected EOF");
            }
        }
        return data;
    }

    private static String contentTypeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
