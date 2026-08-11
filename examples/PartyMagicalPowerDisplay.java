// Example: show a chat line with a party member's current Magical Power the moment they join your
// party. Two pieces working together: (1) your mod's own party-join event (Hypixel parties aren't
// visible to sky.melloo.me - you have to tell it who joined), (2) a signed v1 API call to look up
// that player's stats.
//
// This is illustrative, not a drop-in class - it assumes you already have Ed25519 signing wired up
// per DEVELOPER_API.md section 3 (SignedRequest.send(...) below stands in for that). Adjust the
// stats JSON path to whatever the live response actually contains - check it against a real call
// before shipping, response shapes are documented as evolvable (see DEVELOPER_API.md section 14).
package example;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class PartyMagicalPowerDisplay {

    private static final Gson GSON = new Gson();

    /** Call this from your own party-join event/listener - not part of this API, that's mod-side. */
    public static void onPartyMemberJoined(UUID uuid, String username) {
        // Fire-and-forget from the game thread's perspective - don't block gameplay on a network call.
        SignedRequest
            .getAsync("/player/" + username)
            .thenAccept(PartyMagicalPowerDisplay::handlePlayerResponse)
            .exceptionally(err -> {
                // Never let a failed lookup do anything more than skip the message - see
                // DEVELOPER_API.md's "resilient request wrapper" example for the general pattern.
                return null;
            });
    }

    private static void handlePlayerResponse(HttpResponse<String> response) {
        if (response.statusCode() == 429) {
            // Back off - see DEVELOPER_API.md section 5's recommended 429 handling. Don't retry
            // immediately in a tight loop.
            return;
        }
        if (response.statusCode() != 200) return;

        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        // Adjust this path to the real response shape - illustrative placeholder.
        Integer magicalPower = readIntPath(body, "stats", "magical_power");
        String displayName = body.has("displayName") ? body.get("displayName").getAsString() : body.toString();

        if (magicalPower != null) {
            ChatUtil.sendMessage(displayName + " joined - Magical Power: " + magicalPower);
        }
    }

    private static Integer readIntPath(JsonObject root, String... path) {
        JsonObject current = root;
        for (int i = 0; i < path.length - 1; i++) {
            if (current == null || !current.has(path[i])) return null;
            current = current.getAsJsonObject(path[i]);
        }
        String last = path[path.length - 1];
        if (current == null || !current.has(last)) return null;
        return current.get(last).getAsInt();
    }

    // Stand-ins for the real infrastructure a mod already has - shown here only so the example reads
    // top-to-bottom. Real implementations: DEVELOPER_API.md section 3 (signing) and your own chat API.
    private static class SignedRequest {
        static java.util.concurrent.CompletableFuture<HttpResponse<String>> getAsync(String path) {
            throw new UnsupportedOperationException("Wire this to your real signed-request client - see DEVELOPER_API.md section 3 and 19.");
        }
    }

    private static class ChatUtil {
        static void sendMessage(String text) {
            throw new UnsupportedOperationException("Wire this to your mod's own chat output.");
        }
    }
}
