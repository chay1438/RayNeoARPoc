package com.example.indoorar.server.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = "jdbc:postgresql://ep-square-sky-apwktlhn-pooler.c-7.us-east-1.aws.neon.tech/neondb?sslmode=require"
        val user = "neondb_owner"
        val password = "npg_yoMSvZ8OBW3D"
        
        val database = Database.connect(jdbcURL, driverClassName, user, password)
        
        transaction(database) {
            SchemaUtils.create(MapLocations, NavNodes, NavEdges, Waypoints)
        }
    }
}
