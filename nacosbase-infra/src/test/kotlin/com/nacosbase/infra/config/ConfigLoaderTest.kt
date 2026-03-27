package com.nacosbase.infra.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigLoaderTest {

    // --- interpolate tests ---

    @Test
    fun `interpolate replaces variable when env var is set`() {
        val result = ConfigLoader.interpolate("\${NACOSBASE_TEST_VAR}") { name ->
            if (name == "NACOSBASE_TEST_VAR") "hello" else null
        }
        assertEquals("hello", result)
    }

    @Test
    fun `interpolate uses default value when env var is absent and default provided`() {
        val result = ConfigLoader.interpolate("\${NACOSBASE_MISSING:-fallback}") { null }
        assertEquals("fallback", result)
    }

    @Test
    fun `interpolate uses empty string default when env var absent and default is empty`() {
        val result = ConfigLoader.interpolate("\${NACOSBASE_MISSING:-}") { null }
        assertEquals("", result)
    }

    @Test
    fun `interpolate throws IllegalStateException when env var absent and no default`() {
        assertFailsWith<IllegalStateException> {
            ConfigLoader.interpolate("\${NACOSBASE_DEFINITELY_NOT_SET_XYZ}") { null }
        }
    }

    @Test
    fun `interpolate error message mentions the variable name`() {
        val ex = assertFailsWith<IllegalStateException> {
            ConfigLoader.interpolate("\${MY_VAR}") { null }
        }
        assertTrue(ex.message?.contains("MY_VAR") == true, "Error message should mention the variable name")
    }

    @Test
    fun `interpolate replaces multiple variables in one string`() {
        val result = ConfigLoader.interpolate("host=\${HOST} port=\${PORT:-8848}") { name ->
            when (name) {
                "HOST" -> "localhost"
                else -> null
            }
        }
        assertEquals("host=localhost port=8848", result)
    }

    @Test
    fun `interpolate leaves plain text untouched`() {
        val input = "no variables here"
        assertEquals(input, ConfigLoader.interpolate(input) { null })
    }

    @Test
    fun `interpolate env var takes precedence over default`() {
        val result = ConfigLoader.interpolate("\${VAR:-default}") { "actual" }
        assertEquals("actual", result)
    }

    // --- load tests ---

    @Test
    fun `load parses valid YAML into NacosbaseConfig`() {
        val yaml = """
            nacos:
              serverAddr: "127.0.0.1:8848"
              username: "nacos"
              password: "nacos"
              defaultNamespace: "dev"
            datasource:
              url: "jdbc:mysql://localhost:3306/nacosbase"
              username: "root"
              password: "root"
        """.trimIndent()

        val tmpFile = Files.createTempFile("nacosbase-test", ".yml").toFile()
        try {
            tmpFile.writeText(yaml)
            val result = ConfigLoader.load(tmpFile.toPath())
            assertTrue(result.isSuccess, "Expected successful parse but got: ${result.exceptionOrNull()}")
            val config = result.getOrThrow()
            assertEquals("127.0.0.1:8848", config.nacos.serverAddr)
            assertEquals("nacos", config.nacos.username)
            assertEquals("dev", config.nacos.defaultNamespace)
            assertEquals("jdbc:mysql://localhost:3306/nacosbase", config.datasource.url)
            // defaults
            assertEquals("./changelogs", config.changelog.scriptsDir)
            assertEquals("nacosbase", config.changelog.appliedBy)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `load returns failure for non-existent file`() {
        val result = ConfigLoader.load(java.nio.file.Path.of("/nonexistent/nacosbase.yml"))
        assertFalse(result.isSuccess, "Expected failure for missing file")
    }

    @Test
    fun `load applies env-var interpolation before parsing`() {
        val yaml = """
            nacos:
              serverAddr: "${'$'}{NACOS_ADDR:-192.168.1.1:8848}"
              username: "nacos"
              password: "nacos"
              defaultNamespace: "dev"
            datasource:
              url: "jdbc:mysql://localhost:3306/nacosbase"
              username: "root"
              password: "root"
        """.trimIndent()

        val tmpFile = Files.createTempFile("nacosbase-interp-test", ".yml").toFile()
        try {
            tmpFile.writeText(yaml)
            // Controlled envLookup: NACOS_ADDR not set → default value used
            val result = ConfigLoader.load(tmpFile.toPath()) { null }
            assertTrue(result.isSuccess, "Expected successful parse: ${result.exceptionOrNull()}")
            val config = result.getOrThrow()
            assertEquals("192.168.1.1:8848", config.nacos.serverAddr)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `NacosbaseConfig has correct defaults for changelog`() {
        val changelog = ChangelogConfig()
        assertEquals("./changelogs", changelog.scriptsDir)
        assertEquals("nacosbase", changelog.appliedBy)
    }
}
