package com.nacosbase.core.util

import com.nacosbase.core.model.ConfigType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentFlattenerTest {

    // ── assemble ─────────────────────────────────────────────────────────────

    @Test
    fun `assemble YAML numeric value is unquoted`() {
        val result = ContentFlattener.assemble(listOf("port" to "6379"), ConfigType.YAML)
        assertEquals("port: 6379", result)
    }

    @Test
    fun `assemble YAML multiple keys are sorted alphabetically`() {
        val result = ContentFlattener.assemble(
            listOf("timeout" to "3000", "host" to "localhost", "port" to "6379"),
            ConfigType.YAML,
        )
        assertEquals("host: localhost\nport: 6379\ntimeout: 3000", result)
    }

    @Test
    fun `assemble PROPERTIES produces key=value lines sorted alphabetically`() {
        val result = ContentFlattener.assemble(
            listOf("server.port" to "8080", "app.name" to "nacosbase"),
            ConfigType.PROPERTIES,
        )
        assertEquals("app.name=nacosbase\nserver.port=8080", result)
    }

    @Test
    fun `assemble JSON produces sorted keys`() {
        val result = ContentFlattener.assemble(
            listOf("name" to "nacosbase", "env" to "dev"),
            ConfigType.JSON,
        )
        // keys must be alphabetically ordered in the JSON output
        val envIdx = result.indexOf("\"env\"")
        val nameIdx = result.indexOf("\"name\"")
        assertTrue(envIdx < nameIdx, "env should appear before name in sorted JSON")
    }

    // ── flatten ───────────────────────────────────────────────────────────────

    @Test
    fun `flatten then assemble round-trips YAML with numeric values`() {
        val original = "host: redis\nport: 6379\ntimeout: 3000"
        val pairs = ContentFlattener.flatten(original, ConfigType.YAML)
        val restored = ContentFlattener.assemble(pairs, ConfigType.YAML)
        assertEquals(original, restored)
    }
}
