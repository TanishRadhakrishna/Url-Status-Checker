import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Multithreaded URL Status Checker
 *
 * Usage:
 * 1) With command-line URLs:
 *    java UrlStatusChecker https://example.com https://google.com
 *
 * 2) Without args: paste URLs one per line, then an empty line to start checking.
 *
 * Features:
 * - Uses ExecutorService and Callable to check multiple URLs concurrently.
 * - For each URL: reports HTTP response code, response time (ms), content-length (if available).
 * - Has connection and read timeouts to avoid hanging.
 */
public class UrlStatusChecker {

    // Thread pool size: either number of available processors or a small fixed size
    private static final int POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int CONNECT_TIMEOUT_MS = 5000; // 5 seconds
    private static final int READ_TIMEOUT_MS = 7000;    // 7 seconds
    private static final int DEFAULT_MAX_URLS = 200;

    public static void main(String[] args) {
        List<String> urls = new ArrayList<>();

        if (args != null && args.length > 0) {
            // Use command-line arguments as URLs
            Collections.addAll(urls, args);
        } else {
            // Read URLs from stdin
            System.out.println("Paste one URL per line. Submit an empty line to start checking (or Ctrl+D):");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) break;
                    urls.add(line);
                    if (urls.size() >= DEFAULT_MAX_URLS) {
                        System.out.println("Reached max URL limit (" + DEFAULT_MAX_URLS + "). Proceeding...");
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("Error reading from stdin: " + e.getMessage());
                System.exit(1);
            }
        }

        if (urls.isEmpty()) {
            // Provide a few example URLs if none provided
            System.out.println("No URLs provided. Using example URLs.");
            urls.add("https://www.google.com");
            urls.add("https://www.github.com");
            urls.add("https://example.invalid.url"); // intentionally invalid
            urls.add("http://httpstat.us/200?sleep=3000"); // slow 3s responder
            urls.add("http://httpstat.us/503");
        }

        System.out.printf("Checking %d URLs using a thread pool of %d threads...\n\n", urls.size(), POOL_SIZE);

        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        List<Future<UrlCheckResult>> futures = new ArrayList<>();

        for (String rawUrl : urls) {
            UrlChecker task = new UrlChecker(rawUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            futures.add(pool.submit(task));
        }

        // Gather results
        int index = 1;
        for (Future<UrlCheckResult> future : futures) {
            try {
                UrlCheckResult res = future.get(15, TimeUnit.SECONDS); // overall per-task timeout
                printResult(index++, res);
            } catch (TimeoutException te) {
                System.out.printf("[%02d] TIMED OUT waiting for result (task exceeded 15s)\n", index++);
            } catch (ExecutionException ee) {
                System.out.printf("[%02d] ERROR during execution: %s\n", index++, ee.getCause().getMessage());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.out.printf("[%02d] Interrupted while waiting for results\n", index++);
            }
        }

        pool.shutdownNow();
        System.out.println("\nDone.");
    }

    private static void printResult(int idx, UrlCheckResult r) {
        System.out.printf("[%02d] %s\n", idx, r.getUrl());
        if (!r.isValidUrl()) {
            System.out.println("     → Invalid URL format.");
            return;
        }
        if (r.isException()) {
            System.out.printf("     → ERROR: %s\n", r.getErrorMessage());
            return;
        }

        System.out.printf("     → Status: %s (%d)\n",
                r.getResponseMessageSafe(), r.getStatusCode());
        System.out.printf("     → Time: %d ms\n", r.getResponseTimeMs());
        String cl = r.getContentLength() >= 0 ? String.valueOf(r.getContentLength()) : "N/A";
        System.out.printf("     → Content-Length: %s\n", cl);
        System.out.printf("     → Verdict: %s\n", humanVerdict(r.getStatusCode(), r.getResponseTimeMs()));
        System.out.println();
    }

    private static String humanVerdict(int statusCode, long responseTimeMs) {
        if (statusCode >= 200 && statusCode < 300) {
            if (responseTimeMs < 1000) return "OK (fast)";
            else if (responseTimeMs < 3000) return "OK (slow)";
            else return "OK (very slow)";
        } else if (statusCode >= 300 && statusCode < 400) {
            return "Redirect";
        } else if (statusCode >= 400 && statusCode < 500) {
            return "Client error";
        } else if (statusCode >= 500) {
            return "Server error";
        } else {
            return "Unknown";
        }
    }

    // --------- UrlChecker (Callable) ---------
    static class UrlChecker implements Callable<UrlCheckResult> {
        private final String rawUrl;
        private final int connectTimeout;
        private final int readTimeout;

        UrlChecker(String rawUrl, int connectTimeout, int readTimeout) {
            this.rawUrl = Objects.requireNonNull(rawUrl);
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }

        @Override
        public UrlCheckResult call() {
            Instant start = Instant.now();

            // Normalize URL: if missing scheme, add http://
            String normalized = rawUrl;
            if (!rawUrl.matches("(?i)https?://.*")) {
                normalized = "http://" + rawUrl;
            }

            URL url;
            try {
                url = new URL(normalized);
            } catch (MalformedURLException mue) {
                return UrlCheckResult.invalid(rawUrl, "Malformed URL: " + mue.getMessage());
            }

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(readTimeout);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", defaultUserAgent());

                // Optional: try to avoid downloading big bodies — use HEAD when possible
                // Some servers don't handle HEAD well, so use GET but don't read body
                Instant before = Instant.now();
                conn.connect();
                int status = conn.getResponseCode();

                long contentLength = -1;
                try {
                    contentLength = conn.getHeaderFieldLong("Content-Length", -1);
                } catch (Exception ignored) {}

                Instant after = Instant.now();
                long timeMs = Duration.between(start, after).toMillis();

                String respMessage = conn.getResponseMessage();
                return UrlCheckResult.success(rawUrl, status, respMessage, timeMs, contentLength);
            } catch (IOException ioe) {
                long timeMs = Duration.between(start, Instant.now()).toMillis();
                return UrlCheckResult.error(rawUrl, ioe.getClass().getSimpleName() + ": " + ioe.getMessage(), timeMs);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private String defaultUserAgent() {
            return "UrlStatusChecker/1.0 (Java)";
        }
    }

    // --------- UrlCheckResult ---------
    static class UrlCheckResult {
        private final String url;
        private final boolean validUrl;
        private final boolean exception;
        private final String errorMessage;
        private final int statusCode;
        private final String responseMessage;
        private final long responseTimeMs;
        private final long contentLength;

        private UrlCheckResult(String url,
                               boolean validUrl,
                               boolean exception,
                               String errorMessage,
                               int statusCode,
                               String responseMessage,
                               long responseTimeMs,
                               long contentLength) {
            this.url = url;
            this.validUrl = validUrl;
            this.exception = exception;
            this.errorMessage = errorMessage;
            this.statusCode = statusCode;
            this.responseMessage = responseMessage;
            this.responseTimeMs = responseTimeMs;
            this.contentLength = contentLength;
        }

        static UrlCheckResult invalid(String url, String reason) {
            return new UrlCheckResult(url, false, true, reason, -1, null, -1, -1);
        }

        static UrlCheckResult success(String url, int statusCode, String responseMessage, long timeMs, long contentLength) {
            return new UrlCheckResult(url, true, false, null, statusCode, responseMessage, timeMs, contentLength);
        }

        static UrlCheckResult error(String url, String errorMessage, long timeMs) {
            return new UrlCheckResult(url, true, true, errorMessage, -1, null, timeMs, -1);
        }

        public String getUrl() { return url; }
        public boolean isValidUrl() { return validUrl; }
        public boolean isException() { return exception; }
        public String getErrorMessage() { return errorMessage; }
        public int getStatusCode() { return statusCode; }
        public String getResponseMessageSafe() { return responseMessage != null ? responseMessage : "N/A"; }
        public long getResponseTimeMs() { return responseTimeMs; }
        public long getContentLength() { return contentLength; }
    }
}
