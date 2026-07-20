package dev.gumi.devices.omicv1.simulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OmiCv1ProfileFixtureConformanceTest {
    @Test
    fun `simulator inventory exactly matches checked owned-unit profile`() {
        val root = checkNotNull(javaClass.getResourceAsStream("/profile.json"))
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }
        val inventory = root.getValue("inventory").jsonObject
        val fixture = inventory.getValue("services").jsonArray.associate { service ->
            val body = service.jsonObject
            body.getValue("uuid").jsonPrimitive.content to
                body.getValue("characteristics").jsonArray.map { characteristic ->
                    val value = characteristic.jsonObject
                    value.getValue("uuid").jsonPrimitive.content to
                        value.getValue("properties").jsonArray.map { it.jsonPrimitive.content }.toSet()
                }.toMap()
        }
        val simulated = OmiCv1V3012Profile.services.associate { service ->
            service.uuid to service.characteristics.associate { characteristic ->
                characteristic.uuid to characteristic.properties.map { it.name.lowercase() }.toSet()
            }
        }

        assertEquals(inventory.getValue("service_count").jsonPrimitive.content.toInt(), simulated.size)
        assertEquals(
            inventory.getValue("characteristic_count").jsonPrimitive.content.toInt(),
            simulated.values.sumOf(Map<*, *>::size),
        )
        assertEquals(fixture, simulated)
    }
}
