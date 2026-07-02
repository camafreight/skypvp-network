package network.skypvp.paper.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class SkinFetcher {
   private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().version(Version.HTTP_2)
         .connectTimeout(Duration.ofSeconds(10L)).build();

   public SkinFetcher() {
   }

   public static CompletableFuture<String[]> fetchSkinAsync(String input) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            if (!input.startsWith("http://") && !input.startsWith("https://")) {
               return fetchFromMojangName(input);
            } else if (input.contains("textures.minecraft.net")) {
               return generateFromMineskinUrl(input);
            } else if (!input.contains("mineskin.org") && !input.contains("minesk.in")) {
               return generateFromMineskinUrl(input);
            } else {
               String id = input.substring(input.lastIndexOf(47) + 1);
               return getFromMineskinId(id);
            }
         } catch (Exception var21) {
            var21.printStackTrace();
            return null;
         }
      });
   }

   private static String[] fetchFromMojangName(String name) throws Exception {
      HttpRequest req1 = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
            .GET().build();
      HttpResponse<String> res1 = HTTP_CLIENT.send(req1, BodyHandlers.ofString());
      if (res1.statusCode() != 200) {
         return null;
      } else {
         JsonObject obj1 = JsonParser.parseString(res1.body()).getAsJsonObject();
         String uuid = obj1.get("id").getAsString();
         HttpRequest req2 = HttpRequest
               .newBuilder(URI
                     .create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"))
               .GET()
               .build();
         HttpResponse<String> res2 = HTTP_CLIENT.send(req2, BodyHandlers.ofString());
         if (res2.statusCode() != 200) {
            return null;
         } else {
            JsonObject obj2 = JsonParser.parseString(res2.body()).getAsJsonObject();
            JsonObject prop = obj2.getAsJsonArray("properties").get(0).getAsJsonObject();
            return new String[] { prop.get("value").getAsString(), prop.get("signature").getAsString() };
         }
      }
   }

   private static String[] generateFromMineskinUrl(String url) throws Exception {
      JsonObject bodyObj = new JsonObject();
      bodyObj.addProperty("url", url);
      bodyObj.addProperty("visibility", 1);
      HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/generate/url"))
            .header("Content-Type", "application/json")
            .header("User-Agent", "SkyPvP-SkinFetcher")
            .POST(BodyPublishers.ofString(bodyObj.toString()))
            .build();
      HttpResponse<String> res = HTTP_CLIENT.send(req, BodyHandlers.ofString());
      if (res.statusCode() != 200) {
         return null;
      } else {
         JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
         JsonObject texture = obj.getAsJsonObject("data").getAsJsonObject("texture");
         return new String[] { texture.get("value").getAsString(), texture.get("signature").getAsString() };
      }
   }

   private static String[] getFromMineskinId(String id) throws Exception {
      HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/get/uuid/" + id))
            .header("User-Agent", "SkyPvP-SkinFetcher")
            .GET()
            .build();
      HttpResponse<String> res = HTTP_CLIENT.send(req, BodyHandlers.ofString());
      if (res.statusCode() != 200) {
         req = HttpRequest.newBuilder(URI.create("https://api.mineskin.org/get/id/" + id))
               .header("User-Agent", "SkyPvP-SkinFetcher").GET().build();
         res = HTTP_CLIENT.send(req, BodyHandlers.ofString());
         if (res.statusCode() != 200) {
            return null;
         }
      }

      JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
      JsonObject texture = obj.getAsJsonObject("data").getAsJsonObject("texture");
      return new String[] { texture.get("value").getAsString(), texture.get("signature").getAsString() };
   }
}
