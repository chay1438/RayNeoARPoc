import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking

suspend fun main() {
    val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }
    try {
        val payload = """
        {
          "id": "e55139a0-6b60-44ec-b87d-8153cfc584a5",
          "name": "Test",
          "cloudAnchorId": "test",
          "navGraph": {
            "nodes": [],
            "edges": [],
            "waypoints": []
          }
        }
        """.trimIndent()
        val response = client.post("https://rayneoarpoc.onrender.com/maps/upload") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        println("Status: ${response.status}")
    } catch(e: Exception) {
        e.printStackTrace()
    }
}
