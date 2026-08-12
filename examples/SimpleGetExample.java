// Example: the simplest possible call - an unauthenticated GET (see DEVELOPER_API.md section 6).
// Fully real and runnable as-is, no stand-ins - a good place to start.
package example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class SimpleGetExample {

    private static final String BASE_URL = "https://sky.melloo.me/api/public/mod/v1";
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/version-check?version=1.0.0"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
        System.out.println("Compatible: " + body.get("compatible").getAsBoolean());
        System.out.println("Latest version: " + body.get("latestVersion").getAsString());
    }
}
