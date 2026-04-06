# Concurrent Diagnostics Server

Java 17 HTTP server using `ServerSocket` / `Socket`. Reads the request line, handles GET and HEAD, serves files from `public/`, and serves `GET /diagnostics` as plain-text JVM stats.

Uses a cached thread pool so `accept()` stays in the main loop while worker threads handle each client; connections are closed after the response. Request paths are checked for `..` and resolved so files stay under the document root. Responses set `Content-type`, `Content-length`, and `Connection: close`.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/io/github/henryhai/diagnostics/WebServer.java` | Server |
| `public/` | Document root |
| `pom.xml` | Maven build |

## Requirements

JDK 17+ and Maven 3.9+.

## Build and run

```bash
mvn -q package
java -jar target/concurrent-diagnostics-server-1.0.0.jar
```

Or:

```bash
mvn -q exec:java
```

Port defaults to 8080 (first argument or `DIAGNOSTICS_PORT`). Document root defaults to `./public` (`DIAGNOSTICS_WEB_ROOT` to override).

Build output is under `target/` (gitignored).

```bash
curl -s http://127.0.0.1:8080/diagnostics
```

## License

MIT — see `LICENSE`.
