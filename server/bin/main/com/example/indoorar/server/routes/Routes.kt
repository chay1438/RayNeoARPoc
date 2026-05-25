package com.example.indoorar.server.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import com.example.indoorar.server.database.*
import com.example.indoorar.shared.models.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("IndoorAR Navigation Server is running!")
        }

        route("/maps") {
            get {
                val maps = transaction {
                    MapLocations.selectAll().map {
                        MapLocation(
                            id = it[MapLocations.id],
                            name = it[MapLocations.name],
                            cloudAnchorId = it[MapLocations.cloudAnchorId],
                            navGraph = NavGraph(emptyList(), emptyList(), emptyList()) // Metadata only
                        )
                    }
                }
                call.respond(maps)
            }

            post("/upload") {
                try {
                    val mapLocation = call.receive<MapLocation>()

                    transaction {
                        // Save Map Location
                        MapLocations.insert {
                            it[id] = mapLocation.id
                            it[name] = mapLocation.name
                            it[cloudAnchorId] = mapLocation.cloudAnchorId
                        }

                        // Save Nodes (walkable path nodes + named object nodes)
                        mapLocation.navGraph.nodes.forEach { node ->
                            NavNodes.insert {
                                it[id]    = node.id
                                it[mapId] = mapLocation.id
                                it[x]     = node.position.x
                                it[y]     = node.position.y
                                it[z]     = node.position.z
                            }
                        }

                        // Save Edges
                        mapLocation.navGraph.edges.forEach { edge ->
                            NavEdges.insert {
                                it[mapId]   = mapLocation.id
                                it[nodeAId] = edge.nodeAId
                                it[nodeBId] = edge.nodeBId
                                it[weight]  = edge.weight
                            }
                        }

                        // Save named Waypoints
                        mapLocation.navGraph.waypoints.forEach { wp ->
                            Waypoints.insert {
                                it[id]    = wp.id
                                it[mapId] = mapLocation.id
                                it[name]  = wp.name
                                it[x]     = wp.position.x
                                it[y]     = wp.position.y
                                it[z]     = wp.position.z
                            }
                        }
                    }

                    call.respond(HttpStatusCode.Created, "Map uploaded successfully")
                } catch (e: Exception) {
                    // Log the real error and send it back so the Android client can display it
                    application.environment.log.error("Map upload failed", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Upload error: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                
                val map = transaction {
                    val mapRow = MapLocations.selectAll().where { MapLocations.id eq id }.singleOrNull()
                        ?: return@transaction null
                        
                    val nodes = NavNodes.selectAll().where { NavNodes.mapId eq id }.map {
                        NavNode(
                            id = it[NavNodes.id],
                            position = Vector3(it[NavNodes.x], it[NavNodes.y], it[NavNodes.z])
                        )
                    }
                    
                    val edges = NavEdges.selectAll().where { NavEdges.mapId eq id }.map {
                        NavEdge(
                            nodeAId = it[NavEdges.nodeAId],
                            nodeBId = it[NavEdges.nodeBId],
                            weight = it[NavEdges.weight]
                        )
                    }

                    val waypoints = Waypoints.selectAll().where { Waypoints.mapId eq id }.map {
                        Waypoint(
                            id = it[Waypoints.id],
                            name = it[Waypoints.name],
                            position = Vector3(it[Waypoints.x], it[Waypoints.y], it[Waypoints.z])
                        )
                    }
                    
                    MapLocation(
                        id = mapRow[MapLocations.id],
                        name = mapRow[MapLocations.name],
                        cloudAnchorId = mapRow[MapLocations.cloudAnchorId],
                        navGraph = NavGraph(nodes, edges, waypoints)
                    )
                }
                
                if (map == null) {
                    call.respond(HttpStatusCode.NotFound, "Map not found")
                } else {
                    call.respond(map)
                }
            }
        }
        route("/products") {
            get("/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                
                val product = transaction {
                    val row = Products.selectAll().where { Products.id eq id }.singleOrNull()
                        ?: return@transaction null
                    Product(
                        id = row[Products.id],
                        title = row[Products.title],
                        description = row[Products.description],
                        imageUrl = row[Products.imageUrl],
                        url = row[Products.url],
                        price = row[Products.price],
                        discountedPrice = row[Products.discountedPrice],
                        discount = row[Products.discount]
                    )
                }
                
                if (product == null) {
                    call.respond(HttpStatusCode.NotFound, "Product not found")
                } else {
                    call.respond(product)
                }
            }
        }
    }
}
