package com.example.indoorar.server.database

import org.jetbrains.exposed.sql.Table

object MapLocations : Table() {
    val id = varchar("id", 50)
    val name = varchar("name", 100)
    val cloudAnchorId = varchar("cloud_anchor_id", 100)
    override val primaryKey = PrimaryKey(id)
}

object NavNodes : Table() {
    val id = varchar("id", 50)
    val mapId = varchar("map_id", 50) references MapLocations.id
    val x = float("x")
    val y = float("y")
    val z = float("z")
    override val primaryKey = PrimaryKey(id)
}

object NavEdges : Table() {
    val id = integer("id").autoIncrement()
    val mapId = varchar("map_id", 50) references MapLocations.id
    val nodeAId = varchar("node_a_id", 50) references NavNodes.id
    val nodeBId = varchar("node_b_id", 50) references NavNodes.id
    val weight = float("weight")
    override val primaryKey = PrimaryKey(id)
}

object Waypoints : Table() {
    val id = varchar("id", 50)
    val mapId = varchar("map_id", 50) references MapLocations.id
    val name = varchar("name", 100)
    val x = float("x")
    val y = float("y")
    val z = float("z")
    override val primaryKey = PrimaryKey(id)
}

object Products : Table() {
    val id = varchar("id", 100)
    val title = text("title")
    val description = text("description").nullable()
    val imageUrl = varchar("image_url", 500).nullable()
    val url = varchar("url", 500).nullable()
    val price = double("price").nullable()
    val discountedPrice = double("discounted_price").nullable()
    val discount = double("discount").nullable()
    
    override val primaryKey = PrimaryKey(id)
}
