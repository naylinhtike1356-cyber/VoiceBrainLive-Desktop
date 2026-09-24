import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.prefs.Preferences

fun main() {
    val prefs = Preferences.userRoot().node("VoiceBrainLive")
    val key = prefs.get("gemini_api_key", "").trim()
    println("Stored API Key exists: ${key.isNotBlank()} (length: ${key.length})")
    if (key.isBlank()) return

    val client = HttpClient.newHttpClient()
    val req = HttpRequest.newBuilder()
        .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=$key"))
        .GET()
        .build()

    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    println("Models status: ${resp.statusCode()}")
    val body = resp.body()
    // Print all models that contain 'live' or 'realtime' or '2.0-flash'
    body.lines().filter { it.contains("\"name\": \"models/") }.forEach {
        println(it.trim())
    }
}
