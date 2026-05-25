package com.example.indoorar.network

import com.example.indoorar.shared.models.MapLocation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object NetworkManager {
    // If testing on local emulator, use 10.0.2.2. If on physical device like AR Glasses, use your computer's local IP
    // For now, let's use 10.0.2.2 as a placeholder
    private const val BASE_URL = "https://rayneoarpoc.onrender.com"
    
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    /**
     * Uploads a surveyed MapLocation to the server.
     * Returns Result.success on HTTP 201.
     * Returns Result.failure with a descriptive message on any network error or non-201 response,
     * so the caller can show the real reason to the user instead of a generic "Failed".
     */
    suspend fun uploadMap(mapLocation: MapLocation): Result<Unit> {
        return try {
            val response = client.post("$BASE_URL/maps/upload") {
                contentType(ContentType.Application.Json)
                setBody(mapLocation)
            }
            if (response.status == HttpStatusCode.Created) {
                Result.success(Unit)
            } else {
                // Read the server's error body (set by the try-catch in Routes.kt)
                val serverMessage = try { response.bodyAsText() } catch (_: Exception) { "no response body" }
                Result.failure(Exception("HTTP ${response.status.value} — $serverMessage"))
            }
        } catch (e: Exception) {
            // Network-level failures: timeout, no connectivity, SSL error, etc.
            android.util.Log.e("NetworkManager", "uploadMap failed", e)
            Result.failure(Exception(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun downloadMap(id: String): MapLocation? {
        return try {
            val response = client.get("$BASE_URL/maps/$id")
            if (response.status == HttpStatusCode.OK) {
                response.body<MapLocation>()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    suspend fun getMaps(): List<MapLocation> {
        return try {
            val response = client.get("$BASE_URL/maps")
            if (response.status == HttpStatusCode.OK) {
                response.body<List<MapLocation>>()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    suspend fun getProduct(id: String): com.example.indoorar.shared.models.Product? {
        return try {
            val response = client.get("$BASE_URL/products/$id")
            if (response.status == HttpStatusCode.OK) {
                response.body<com.example.indoorar.shared.models.Product>()
            } else {
                // FALLBACK: The server on Render might not be deployed yet with the new /products route.
                // Return a mock product so the AR scanning UI can be tested.
                com.example.indoorar.shared.models.Product(
                    id = id,
                    title = "ECOVACS DEEBOT N30 Plus (Mock)",
                    description = "2 in 1 Robot Vacuum & Mop, 2025 New Launch. (Server route not deployed)",
                    imageUrl = "https://m.media-amazon.com/images/I/6166RQH8dIL._SL1500_.jpg",
                    url = "https://amzn.to/4tboiId",
                    price = 89999.0,
                    discountedPrice = 29999.0,
                    discount = 67.0
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback for network error
            com.example.indoorar.shared.models.Product(
                id = id,
                title = "ECOVACS DEEBOT N30 Plus (Mock)",
                description = "2 in 1 Robot Vacuum & Mop, 2025 New Launch. (Network Error)",
                imageUrl = "https://m.media-amazon.com/images/I/6166RQH8dIL._SL1500_.jpg",
                url = "https://amzn.to/4tboiId",
                price = 89999.0,
                discountedPrice = 29999.0,
                discount = 67.0
            )
        }
    }
}
