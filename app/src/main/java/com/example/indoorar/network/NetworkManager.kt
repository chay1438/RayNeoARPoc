package com.example.indoorar.network

import com.example.indoorar.shared.models.MapLocation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
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

    suspend fun uploadMap(mapLocation: MapLocation): Boolean {
        return try {
            val response = client.post("$BASE_URL/maps/upload") {
                contentType(ContentType.Application.Json)
                setBody(mapLocation)
            }
            response.status == HttpStatusCode.Created
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
}
