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
    fun `assemble YAML boolean value is unquoted`() {
        val result = ContentFlattener.assemble(listOf("enabled" to "true"), ConfigType.YAML)
        assertEquals("enabled: true", result)
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
    fun `assemble YAML nested keys via dot-notation`() {
        val result = ContentFlattener.assemble(
            listOf("server.host" to "localhost", "server.port" to "8080"),
            ConfigType.YAML,
        )
        assertEquals("server:\n  host: localhost\n  port: 8080", result)
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
        val envIdx = result.indexOf("\"env\"")
        val nameIdx = result.indexOf("\"name\"")
        assertTrue(envIdx < nameIdx, "env should appear before name in sorted JSON")
    }

    @Test
    fun `assemble JSON nested keys via dot-notation`() {
        val result = ContentFlattener.assemble(
            listOf("db.host" to "localhost", "db.port" to "3306"),
            ConfigType.JSON,
        )
        assertTrue(result.contains("\"db\""))
        assertTrue(result.contains("\"host\""))
        assertTrue(result.contains("\"port\""))
    }

    @Test
    fun `assemble TEXT returns raw value`() {
        val result = ContentFlattener.assemble(listOf("" to "raw text content"), ConfigType.TEXT)
        assertEquals("raw text content", result)
    }

    @Test
    fun `assemble TEXT with empty list returns empty string`() {
        val result = ContentFlattener.assemble(emptyList(), ConfigType.TEXT)
        assertEquals("", result)
    }

    // ── flatten ───────────────────────────────────────────────────────────────

    @Test
    fun `flatten then assemble round-trips YAML with numeric values`() {
        val original = "host: redis\nport: 6379\ntimeout: 3000"
        val pairs = ContentFlattener.flatten(original, ConfigType.YAML)
        val restored = ContentFlattener.assemble(pairs, ConfigType.YAML)
        assertEquals(original, restored)
    }

    @Test
    fun `flatten YAML returns key-value pairs`() {
        val pairs = ContentFlattener.flatten("host: redis\nport: 6379", ConfigType.YAML)
        assertEquals(listOf("host" to "redis", "port" to "6379"), pairs)
    }

    @Test
    fun `flatten YAML nested structure uses dot-notation keys`() {
        val content = "server:\n  host: localhost\n  port: 8080"
        val pairs = ContentFlattener.flatten(content, ConfigType.YAML)
        assertTrue(pairs.any { it.first == "server.host" && it.second == "localhost" })
        assertTrue(pairs.any { it.first == "server.port" && it.second == "8080" })
    }

    @Test
    fun `flatten YAML empty content returns empty list`() {
        val pairs = ContentFlattener.flatten("", ConfigType.YAML)
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `flatten YAML list value returns block sequence string`() {
        val content = "servers:\n  - 192.168.1.1\n  - 192.168.1.2"
        val pairs = ContentFlattener.flatten(content, ConfigType.YAML)
        val serversPair = pairs.find { it.first == "servers" }
        assertTrue(serversPair != null, "Expected 'servers' key")
        assertTrue(serversPair!!.second.contains("192.168.1.1"))
        assertTrue(serversPair.second.contains("192.168.1.2"))
    }

    @Test
    fun `flatten PROPERTIES returns sorted key-value pairs`() {
        val content = "server.port=8080\napp.name=nacosbase"
        val pairs = ContentFlattener.flatten(content, ConfigType.PROPERTIES)
        assertEquals(listOf("app.name" to "nacosbase", "server.port" to "8080"), pairs)
    }

    @Test
    fun `flatten PROPERTIES round-trips correctly`() {
        val original = "app.name=nacosbase\nserver.port=8080"
        val pairs = ContentFlattener.flatten(original, ConfigType.PROPERTIES)
        val restored = ContentFlattener.assemble(pairs, ConfigType.PROPERTIES)
        assertEquals(original, restored)
    }

    @Test
    fun `flatten JSON returns dot-notation key-value pairs`() {
        val content = """{"host":"localhost","port":"3306"}"""
        val pairs = ContentFlattener.flatten(content, ConfigType.JSON)
        assertTrue(pairs.any { it.first == "host" && it.second == "localhost" })
        assertTrue(pairs.any { it.first == "port" && it.second == "3306" })
    }

    @Test
    fun `flatten JSON nested object uses dot-notation keys`() {
        val content = """{"db":{"host":"localhost","port":"3306"}}"""
        val pairs = ContentFlattener.flatten(content, ConfigType.JSON)
        assertTrue(pairs.any { it.first == "db.host" && it.second == "localhost" })
        assertTrue(pairs.any { it.first == "db.port" && it.second == "3306" })
    }

    @Test
    fun `flatten JSON array returns stringified array as value`() {
        val content = """{"servers":["192.168.1.1","192.168.1.2"]}"""
        val pairs = ContentFlattener.flatten(content, ConfigType.JSON)
        val serversPair = pairs.find { it.first == "servers" }
        assertTrue(serversPair != null)
        assertTrue(serversPair!!.second.contains("192.168.1.1"))
    }

    @Test
    fun `flatten then assemble round-trips JSON`() {
        val original = """{"host":"localhost","port":"3306"}"""
        val pairs = ContentFlattener.flatten(original, ConfigType.JSON)
        val restored = ContentFlattener.assemble(pairs, ConfigType.JSON)
        // Keys should be preserved (order might differ, compare parsed)
        assertTrue(restored.contains("\"host\""))
        assertTrue(restored.contains("\"localhost\""))
    }

    @Test
    fun `flatten TEXT returns single pair with empty key`() {
        val pairs = ContentFlattener.flatten("raw text content", ConfigType.TEXT)
        assertEquals(listOf("" to "raw text content"), pairs)
    }

    // ── checkAppendable ───────────────────────────────────────────────────────

    @Test
    fun `checkAppendable YAML scalar key is appendable`() {
        val content = "host: localhost"
        val result = ContentFlattener.checkAppendable(content, ConfigType.YAML, "host")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkAppendable YAML list key is appendable`() {
        val content = "servers:\n  - 192.168.1.1"
        val result = ContentFlattener.checkAppendable(content, ConfigType.YAML, "servers")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkAppendable YAML map key is not appendable`() {
        val content = "server:\n  host: localhost"
        val result = ContentFlattener.checkAppendable(content, ConfigType.YAML, "server")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("nested object"))
    }

    @Test
    fun `checkAppendable YAML missing key returns failure`() {
        val content = "host: localhost"
        val result = ContentFlattener.checkAppendable(content, ConfigType.YAML, "missing")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found"))
    }

    @Test
    fun `checkAppendable YAML nested key is appendable`() {
        val content = "server:\n  hosts:\n    - localhost"
        val result = ContentFlattener.checkAppendable(content, ConfigType.YAML, "server.hosts")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkAppendable JSON scalar key is appendable`() {
        val content = """{"host":"localhost"}"""
        val result = ContentFlattener.checkAppendable(content, ConfigType.JSON, "host")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkAppendable JSON array key is appendable`() {
        val content = """{"servers":["192.168.1.1"]}"""
        val result = ContentFlattener.checkAppendable(content, ConfigType.JSON, "servers")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkAppendable JSON object key is not appendable`() {
        val content = """{"db":{"host":"localhost"}}"""
        val result = ContentFlattener.checkAppendable(content, ConfigType.JSON, "db")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("nested object"))
    }

    @Test
    fun `checkAppendable PROPERTIES returns unsupported failure`() {
        val result = ContentFlattener.checkAppendable("key=value", ConfigType.PROPERTIES, "key")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not supported"))
    }

    @Test
    fun `checkAppendable TEXT returns unsupported failure`() {
        val result = ContentFlattener.checkAppendable("some text", ConfigType.TEXT, "")
        assertTrue(result.isFailure)
    }

    // ── appendToKey ───────────────────────────────────────────────────────────

    @Test
    fun `appendToKey YAML promotes scalar to list`() {
        val content = "host: localhost"
        val result = ContentFlattener.appendToKey(content, ConfigType.YAML, "host", "redis")
        assertTrue(result.isSuccess)
        val yaml = result.getOrThrow()
        assertTrue(yaml.contains("localhost"))
        assertTrue(yaml.contains("redis"))
    }

    @Test
    fun `appendToKey YAML appends to existing list`() {
        val content = "servers:\n  - 192.168.1.1"
        val result = ContentFlattener.appendToKey(content, ConfigType.YAML, "servers", "192.168.1.2")
        assertTrue(result.isSuccess)
        val yaml = result.getOrThrow()
        assertTrue(yaml.contains("192.168.1.1"))
        assertTrue(yaml.contains("192.168.1.2"))
    }

    @Test
    fun `appendToKey YAML nested key appends correctly`() {
        val content = "server:\n  hosts:\n    - localhost"
        val result = ContentFlattener.appendToKey(content, ConfigType.YAML, "server.hosts", "redis")
        assertTrue(result.isSuccess)
        val yaml = result.getOrThrow()
        assertTrue(yaml.contains("localhost"))
        assertTrue(yaml.contains("redis"))
    }

    @Test
    fun `appendToKey YAML map key returns failure`() {
        val content = "server:\n  host: localhost"
        val result = ContentFlattener.appendToKey(content, ConfigType.YAML, "server", "extra")
        assertTrue(result.isFailure)
    }

    @Test
    fun `appendToKey YAML missing key returns failure`() {
        val content = "host: localhost"
        val result = ContentFlattener.appendToKey(content, ConfigType.YAML, "missing", "value")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found"))
    }

    @Test
    fun `appendToKey JSON promotes scalar to array`() {
        val content = """{"host":"localhost"}"""
        val result = ContentFlattener.appendToKey(content, ConfigType.JSON, "host", "redis")
        assertTrue(result.isSuccess)
        val json = result.getOrThrow()
        assertTrue(json.contains("localhost"))
        assertTrue(json.contains("redis"))
    }

    @Test
    fun `appendToKey JSON appends to existing array`() {
        val content = """{"servers":["192.168.1.1"]}"""
        val result = ContentFlattener.appendToKey(content, ConfigType.JSON, "servers", "192.168.1.2")
        assertTrue(result.isSuccess)
        val json = result.getOrThrow()
        assertTrue(json.contains("192.168.1.1"))
        assertTrue(json.contains("192.168.1.2"))
    }

    @Test
    fun `appendToKey JSON nested key appends correctly`() {
        val content = """{"server":{"hosts":["localhost"]}}"""
        val result = ContentFlattener.appendToKey(content, ConfigType.JSON, "server.hosts", "redis")
        assertTrue(result.isSuccess)
        val json = result.getOrThrow()
        assertTrue(json.contains("localhost"))
        assertTrue(json.contains("redis"))
    }

    @Test
    fun `appendToKey JSON object key returns failure`() {
        val content = """{"db":{"host":"localhost"}}"""
        val result = ContentFlattener.appendToKey(content, ConfigType.JSON, "db", "extra")
        assertTrue(result.isFailure)
    }

    @Test
    fun `appendToKey PROPERTIES returns unsupported failure`() {
        val result = ContentFlattener.appendToKey("key=value", ConfigType.PROPERTIES, "key", "new")
        assertTrue(result.isFailure)
    }

    // ── checkListValueRemovable ───────────────────────────────────────────────

    @Test
    fun `checkListValueRemovable YAML value in list succeeds`() {
        val content = "servers:\n  - 192.168.1.1\n  - 192.168.1.2"
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.YAML, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkListValueRemovable YAML value not in list returns failure`() {
        val content = "servers:\n  - 192.168.1.1"
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.YAML, "servers", "10.0.0.1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found"))
    }

    @Test
    fun `checkListValueRemovable YAML key is not a list returns failure`() {
        val content = "host: localhost"
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.YAML, "host", "localhost")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not a list"))
    }

    @Test
    fun `checkListValueRemovable YAML missing key returns failure`() {
        val content = "host: localhost"
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.YAML, "missing", "val")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found"))
    }

    @Test
    fun `checkListValueRemovable JSON value in array succeeds`() {
        val content = """{"servers":["192.168.1.1","192.168.1.2"]}"""
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.JSON, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `checkListValueRemovable JSON value not in array returns failure`() {
        val content = """{"servers":["192.168.1.1"]}"""
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.JSON, "servers", "10.0.0.1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `checkListValueRemovable JSON key is not array returns failure`() {
        val content = """{"host":"localhost"}"""
        val result = ContentFlattener.checkListValueRemovable(content, ConfigType.JSON, "host", "localhost")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not a list"))
    }

    @Test
    fun `checkListValueRemovable PROPERTIES returns unsupported failure`() {
        val result = ContentFlattener.checkListValueRemovable("key=value", ConfigType.PROPERTIES, "key", "value")
        assertTrue(result.isFailure)
    }

    // ── removeListValue ───────────────────────────────────────────────────────

    @Test
    fun `removeListValue YAML removes item from list`() {
        val content = "servers:\n  - 192.168.1.1\n  - 192.168.1.2"
        val result = ContentFlattener.removeListValue(content, ConfigType.YAML, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
        val yaml = result.getOrThrow()
        assertTrue(!yaml.contains("192.168.1.1"))
        assertTrue(yaml.contains("192.168.1.2"))
    }

    @Test
    fun `removeListValue YAML removes key entirely when list becomes empty`() {
        val content = "servers:\n  - 192.168.1.1"
        val result = ContentFlattener.removeListValue(content, ConfigType.YAML, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
        val yaml = result.getOrThrow()
        assertTrue(!yaml.contains("servers"))
    }

    @Test
    fun `removeListValue YAML returns empty string when entire config becomes empty`() {
        val content = "servers:\n  - 192.168.1.1"
        val result = ContentFlattener.removeListValue(content, ConfigType.YAML, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrThrow())
    }

    @Test
    fun `removeListValue YAML nested key removes item from nested list`() {
        val content = "server:\n  hosts:\n    - localhost\n    - redis"
        val result = ContentFlattener.removeListValue(content, ConfigType.YAML, "server.hosts", "localhost")
        assertTrue(result.isSuccess)
        val yaml = result.getOrThrow()
        assertTrue(!yaml.contains("localhost"))
        assertTrue(yaml.contains("redis"))
    }

    @Test
    fun `removeListValue YAML prunes empty intermediate map`() {
        val content = "server:\n  hosts:\n    - localhost"
        val result = ContentFlattener.removeListValue(content, ConfigType.YAML, "server.hosts", "localhost")
        assertTrue(result.isSuccess)
        // server.hosts becomes empty, so server map should be pruned
        assertEquals("", result.getOrThrow())
    }

    @Test
    fun `removeListValue YAML key is not a list returns failure`() {
        val content = "host: localhost"
        val result = ContentFlattener.removeListValue(content, ConfigType.YAML, "host", "localhost")
        assertTrue(result.isFailure)
    }

    @Test
    fun `removeListValue JSON removes item from array`() {
        val content = """{"servers":["192.168.1.1","192.168.1.2"]}"""
        val result = ContentFlattener.removeListValue(content, ConfigType.JSON, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
        val json = result.getOrThrow()
        assertTrue(!json.contains("192.168.1.1"))
        assertTrue(json.contains("192.168.1.2"))
    }

    @Test
    fun `removeListValue JSON removes key when array becomes empty`() {
        val content = """{"host":"localhost","servers":["192.168.1.1"]}"""
        val result = ContentFlattener.removeListValue(content, ConfigType.JSON, "servers", "192.168.1.1")
        assertTrue(result.isSuccess)
        val json = result.getOrThrow()
        assertTrue(!json.contains("servers"))
        assertTrue(json.contains("host"))
    }

    @Test
    fun `removeListValue JSON nested key removes item`() {
        val content = """{"server":{"hosts":["localhost","redis"]}}"""
        val result = ContentFlattener.removeListValue(content, ConfigType.JSON, "server.hosts", "localhost")
        assertTrue(result.isSuccess)
        val json = result.getOrThrow()
        assertTrue(!json.contains("localhost"))
        assertTrue(json.contains("redis"))
    }

    @Test
    fun `removeListValue PROPERTIES returns unsupported failure`() {
        val result = ContentFlattener.removeListValue("key=value", ConfigType.PROPERTIES, "key", "value")
        assertTrue(result.isFailure)
    }
}
