// Example: a signed POST with a body (see DEVELOPER_API.md sections 3 and 8 - saving cloud
// settings). Assumes you already have a live identity from the auth handshake - see
// AuthenticationExample. Real signing and HTTP code, fully runnable given a real uuid/privateKey.
package example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Locale;
import com.google.gson.JsonObject;

public class SignedPostExample {

    private static final String BASE_URL = "https://sky.melloo.me/api/public/mod/v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        String uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"; // from your live identity
        PrivateKey privateKey = null; // from your live identity's Ed25519 key pair

        JsonObject settings = new JsonObject();
        settings.addProperty("showScore", true);
        JsonObject body = new JsonObject();
        body.add("settings", settings);

        // The exact bytes hashed for signing MUST be the exact bytes sent - serialize once, reuse.
        byte[] rawBody = body.toString().getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis();
        String nonce = randomNonce();
        String signature = sign(privateKey, uuid, "POST", "/api/public/mod/v1/settings", timestamp, nonce, rawBody);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/settings"))
                .header("Content-Type", "application/json")
                .header("X-SkyMelloo-UUID", uuid)
                .header("X-SkyMelloo-Timestamp", String.valueOf(timestamp))
                .header("X-SkyMelloo-Nonce", nonce)
                .header("X-SkyMelloo-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(rawBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Status: " + response.statusCode() + " - " + response.body());
    }

    static String sign(PrivateKey privateKey, String uuid, String method, String path, long timestamp, String nonce, byte[] bodyBytes) throws Exception {
        String normalizedUuid = uuid.toLowerCase(Locale.ROOT).replace("-", "");
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bodyBytes));
        String message = String.join("\n", normalizedUuid, method, path, String.valueOf(timestamp), nonce, bodyHash);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(signer.sign());
    }

    static String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
