// Example: handling 429 with the backoff schedule DEVELOPER_API.md section 5 recommends
// (~2-3s, ~5-7s, ~10-15s, then give up) - real, runnable retry logic. HTTP send itself is a
// stand-in; plug in a real request builder (see SimpleGetExample/SignedPostExample).
package example;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;

public class RetryWithBackoffExample {

    // Midpoints of each documented window.
    private static final long[] BACKOFF_MS = {2500, 6000, 12500};

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = sendWithRetry(client, buildRequest(), 0);
        System.out.println("Final status: " + response.statusCode());
    }

    private static HttpResponse<String> sendWithRetry(HttpClient client, HttpRequest request, int attempt) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 429 || attempt >= BACKOFF_MS.length) {
            return response;
        }
        long delay = BACKOFF_MS[attempt] + (long) (BACKOFF_MS[attempt] * 0.2 * ThreadLocalRandom.current().nextDouble());
        Thread.sleep(delay);
        // A real signed request needs a fresh nonce/timestamp per attempt - rebuild it here, don't resend the same one.
        return sendWithRetry(client, buildRequest(), attempt + 1);
    }

    /** Stand-in - build (or re-sign) the actual request here each time this is called. */
    private static HttpRequest buildRequest() {
        throw new UnsupportedOperationException("Build/re-sign your real request here - see SimpleGetExample/SignedPostExample.");
    }
}
