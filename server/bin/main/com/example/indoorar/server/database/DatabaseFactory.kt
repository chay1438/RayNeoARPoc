package com.example.indoorar.server.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = "jdbc:postgresql://ep-square-sky-apwktlhn-pooler.c-7.us-east-1.aws.neon.tech/neondb?sslmode=require"
        val user = "neondb_owner"
        val password = "npg_yoMSvZ8OBW3D"
        
        val database = Database.connect(jdbcURL, driverClassName, user, password)
        
        transaction(database) {
            SchemaUtils.create(MapLocations, NavNodes, NavEdges, Waypoints, Products)
            
            if (Products.selectAll().empty()) {
                val dumpData = listOf(
                    ProductData("09a9d053-9da3-4b6d-b92a-e96e72ca2913", "ECOVACS DEEBOT N30 Plus White 2 in 1 Robot Vacuum &amp; Mop, 2025 New Launch, Bagless Eco-Friendly Multi-Cyclone Auto Empty Station, 10000 Pa Suction, 5200mAh Battery, Covers 3500+sq ft, Zero Tangle 2.0 : Amazon.in: Home &amp; Kitchen", "ECOVACS DEEBOT N30 Plus White 2 in 1 Robot Vacuum &amp; Mop, 2025 New Launch, Bagless Eco-Friendly Multi-Cyclone Auto Empty Station, 10000 Pa Suction, 5200mAh Battery, Covers 3500+sq ft, Zero Tangle 2.0 : Amazon.in: Home &amp; Kitchen", "https://m.media-amazon.com/images/I/6166RQH8dIL._SL1500_.jpg", "https://amzn.to/4tboiId", 89999.0, 29999.0, 67.0),
                    ProductData("0b5b1e6f-7d47-4dd0-a6a2-7b58a66136b5", "Bajaj DMH 90 Neo 90L Desert Air Cooler for Home|For Larger Room|Anti-Bacterial Honeycomb Cooling Pad|High-Speed|Ice Chamber|90Ft Air Throw|3-Speed Control|3 Year Comprehensive Product Warranty|White : Amazon.in: Home &amp; Kitchen", "Top-tier brand (Bajaj) and large capacity with solid performance history.", "https://m.media-amazon.com/images/I/51FR4bUi-cL._SL1500_.jpg", "https://www.amazon.in/dp/B08W29FFB9?tag=anarick2631-21", 16990.0, 12499.0, null),
                    ProductData("0c6f813b-69bf-4397-ba19-30f43813b858", "Liqui Moly Injection Cleaner", "Liqui Moly Injection Cleaner 300ml 2522 : Amazon.in: Car &amp; Motorbike", "https://m.media-amazon.com/images/I/81QVPR9HZ9L._SL1500_.jpg", "https://www.amazon.in/dp/B00448GEGS?tag=anarick2631-21", 729.0, null, null),
                    ProductData("0e1b36f6-6a5e-4abc-9627-1ce3a15e6482", "Samsung 8 kg, 5 star, AI Control, AI Eco Bubble, Wi-Fi, Digital Inverter, Motor, Fully-Automatic Front Load Washing Machine (WW80T504DAX1TL, Hygiene Steam, Inox) : Amazon.in: Home &amp; Kitchen", "High-end capacity and features; Bosch front loaders are industry benchmarks.", "https://m.media-amazon.com/images/I/71nLTgiopHL._SL1500_.jpg", "https://www.amazon.in/dp/B0CPJG92ZS?tag=anarick2631-21", 55900.0, 36490.0, null),
                    ProductData("10197a19-a5de-4394-a48a-9376d8e162f6", "vivo X300 FE 5G (Urban Olive, 12GB RAM, 256GB Storage) with No Cost EMI/Additional Exchange Offers", null, "https://m.media-amazon.com/images/I/71dONrOR2hL._SL1500_.jpg", "https://amzn.to/42yUgTD?tag=anarick2631-21", 119999.0, 79999.0, 33.0),
                    ProductData("11c4d31e-c1db-4f72-af44-a7169d8b58f5", "Samsung Galaxy S26 Ultra 5G (Cobalt Violet, 12GB RAM, 256GB Storage) with Built-in Privacy Display, AI Phone, Photo Assist, Creative Studio, 200MP Camera, 5000mAh Battery and Snapdragon 8 Elite Gen 5 : Amazon.in: Electronics", "Samsung Galaxy S26 Ultra 5G (Cobalt Violet, 12GB RAM, 256GB Storage) with Built-in Privacy Display, AI Phone, Photo Assist, Creative Studio, 200MP Camera, 5000mAh Battery and Snapdragon 8 Elite Gen 5 : Amazon.in: Electronics", "https://m.media-amazon.com/images/I/71xHws+eI5L._SL1500_.jpg", "https://www.amazon.in/dp/B0GL8G49LV?tag=anarick2631-21", 139999.0, null, null),
                    ProductData("121cb7f9-f794-4c0f-95aa-deb2407ce670", "iQOO Neo 10R 5G (Moonknight Titanium, 8GB RAM, 256GB Storage) | Snapdragon 8s Gen 3 Processor | India's Slimmest 6400mAh Battery Smartphone | Segment's Most Stable 90FPS for 5 Hours", "About this item POWERFUL PERFORMACE: The fastest* processor in the segment powered by Snapdragon 8s Gen3 mobile platform with 4nm TSMC process, 1.7Mn+ Antutu Score. In addition to that, it has LPDRR5X, UFS 4.1 only in 256GB variant, 12GB + 12GB extended RAM & IP65 rated for dust and water resistance. SLIMMEST BATTERY: It’s India's Slimmest* 6400mAh battery smartphone with 0.798cm ultra slim design, 80W fast charger & 5 years of battery health. ULTRA STABLE GAMING: 5 hours non-stop most* stable 90fps in the segment. Stable gaming performance with 6043 mm² vapor chamber, an even larger \"chip\" cooling area for enhanced heat dissipation, Bypass Charging, In-built FPS Meter & 2000 Hz Instant Touch Sampling Rate. DISPLAY & CAMERA: Immersive Display Experience with 1.5K 144Hz AMOLED display, 6.78\" Flat Display, 4500nits Local Peak Brightness & 3840Hz PWM dimming. 50 MP Sony IMX882 OIS Portrait Camera with 8 MP Ultra Wide-Angle Camera, 4K video @60fps and 32MP front Camera with 4K video recording. It comes with AI Features- Instant Cut-out, Circle & Search, AI Note assist, AI Translation, Gemini Assist, AI Photo Enhance & AI Erase OPERATING SYSTEM: Funtouch OS 15 based on Android 15 with 3 years of Android & 4 years of security update & 60-Month Smooth Experience. * T & C Apply", "https://m.media-amazon.com/images/I/610NUM9jlxL._SL1200_.jpg", "https://amzn.to/4sR3khD", 33999.0, 28998.0, 15.0),
                    ProductData("131948d2-3c81-431b-a6b6-fc82f1c5c471", "Luminous Inverter &amp; Battery Combo with Trolley for Home, Shop &amp; Office – Hercules 1600 (1500VA/12V) Square Wave Inverter + RC25000 200Ah Tall Tubular Battery | 36M Warranty on Inverter &amp; Battery : Amazon.in: Home &amp; Kitchen", "High popularity", "https://m.media-amazon.com/images/I/611yrunxuFL._SL1500_.jpg", "https://www.amazon.in/dp/B0BFHHQZYL?tag=anarick2631-21", 27099.0, 27099.0, null)
                )

                dumpData.forEach { p ->
                    Products.insert {
                        it[id] = p.id
                        it[title] = p.title
                        it[description] = p.description
                        it[imageUrl] = p.imageUrl
                        it[url] = p.url
                        it[price] = p.price
                        it[discountedPrice] = p.discountedPrice
                        it[discount] = p.discount
                    }
                }
            }
        }
    }
    
    private data class ProductData(
        val id: String, val title: String, val description: String?, val imageUrl: String?, val url: String?, val price: Double?, val discountedPrice: Double?, val discount: Double?
    )
}
