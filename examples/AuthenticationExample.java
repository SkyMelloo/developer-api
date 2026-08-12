// Example: the full auth handshake from DEVELOPER_API.md section 2.1 - generate an Ed25519 key
// pair, get a challenge, complete Mojang's joinServer (your mod's own job, not this API's), verify,
// then sign one request with the resulting identity. Real crypto (JDK only, no dependencies) -
// HTTP itself is a stand-in, see Http below.
package example;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class AuthenticationExample {

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        HttpResponse<String> challengeResponse = Http.get("/auth/challenge", null);
        JsonObject challenge = GSON.fromJson(challengeResponse.body(), JsonObject.class);
        String serverId = challenge.get("serverId").getAsString();
        long clockOffsetMs = challenge.get("serverTime").getAsLong() - System.currentTimeMillis();

        // Your mod's own job - completes Mojang's session-server join proof for serverId. Not part
        // of this API at all; see your Minecraft library's own auth/session APIs for how.
        MojangSession.joinServer(serverId);

        String uuid = MojangSession.currentUuid();
        String username = MojangSession.currentUsername();
        JsonObject verifyBody = new JsonObject();
        verifyBody.addProperty("serverId", serverId);
        verifyBody.addProperty("username", username);
        verifyBody.addProperty("uuid", uuid);
        verifyBody.addProperty("publicKey", publicKeyBase64);
        Http.post("/auth/verify", verifyBody.toString(), null);

        // Identity is now live - sign a real request with it.
        String signature = sign(keyPair.getPrivate(), uuid, "GET", "/api/public/mod/v1/permissions",
                System.currentTimeMillis() + clockOffsetMs, randomNonce(), new byte[0]);
        System.out.println("Signature ready: " + signature);
    }

    /** The exact canonical message from section 3.1, signed with Ed25519. */
    static String sign(PrivateKey privateKey, String uuid, String method, String path, long timestamp, String nonce, byte[] bodyBytes) throws Exception {
        String normalizedUuid = uuid.toLowerCase(Locale.ROOT).replace("-", "");
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bodyBytes));
        String message = String.join("\n", normalizedUuid, method, path, String.valueOf(timestamp), nonce, bodyHash);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    static String randomNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Stand-in - wire this to your real HTTP client (see SimpleGetExample/SignedPostExample for the actual java.net.http calls). */
    private static class Http {
        static HttpResponse<String> get(String path, String signatureHeaders) {
            throw new UnsupportedOperationException("See SimpleGetExample for a real GET call.");
        }

        static HttpResponse<String> post(String path, String body, String signatureHeaders) {
            throw new UnsupportedOperationException("See SignedPostExample for a real POST call.");
        }
    }

    /** Stand-in for your mod's own Minecraft session - not part of this API. */
    private static class MojangSession {
        static void joinServer(String serverId) {
            throw new UnsupportedOperationException("Your Minecraft library's own session-server join call.");
        }

        static String currentUuid() {
            return UUID.randomUUID().toString();
        }

        static String currentUsername() {
            return "ExamplePlayer";
        }
    }
}
