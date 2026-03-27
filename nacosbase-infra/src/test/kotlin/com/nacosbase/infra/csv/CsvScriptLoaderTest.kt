package com.nacosbase.infra.csv

import com.nacosbase.core.model.Action
import com.nacosbase.core.model.ConfigType
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvScriptLoaderTest {

    private val loader = CsvScriptLoader()

    @TempDir
    lateinit var tempDir: Path

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun write(name: String, content: String): File =
        tempDir.resolve(name).toFile().also { it.writeText(content) }

    private val validHeader = "action,dataId,group,namespace,content,type,description"

    // ── 1. Loads a single valid CSV ──────────────────────────────────────────

    @Test
    fun `loads single valid csv with correct fields`() {
        write(
            "001-init-redis.csv",
            """
            $validHeader
            ADD,redis.yml,DEFAULT_GROUP,dev,"server: redis",YAML,init redis
            """.trimIndent(),
        )

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(1, scripts.size)
        val script = scripts[0]
        assertEquals("001-init-redis.csv", script.scriptName)
        assertTrue(script.checksum.matches(Regex("[0-9a-f]{64}")), "checksum should be 64-char hex")
        assertEquals(1, script.changeSets.size)

        val cs = script.changeSets[0]
        assertEquals(Action.ADD, cs.action)
        assertEquals("redis.yml", cs.dataId)
        assertEquals("DEFAULT_GROUP", cs.group)
        assertEquals("dev", cs.namespace)
        assertEquals("server: redis", cs.content)
        assertEquals(ConfigType.YAML, cs.type)
        assertEquals("init redis", cs.description)
    }

    // ── 2. Checksum is SHA-256 of file content ───────────────────────────────

    @Test
    fun `checksum matches sha256 of raw file content`() {
        val rawContent = "$validHeader\nADD,app.yml,G,ns,value,YAML,desc"
        write("1-app.csv", rawContent)

        val scripts = loader.loadOrdered(tempDir)

        val expected = sha256(rawContent)
        assertEquals(expected, scripts[0].checksum)
    }

    // ── 3. Sorts numerically, not lexicographically ──────────────────────────

    @Test
    fun `sorts scripts by numeric prefix ascending not lexicographically`() {
        write("10-tenth.csv", "$validHeader\nADD,d10.yml,G,ns,,YAML,")
        write("2-second.csv", "$validHeader\nADD,d2.yml,G,ns,,YAML,")
        write("1-first.csv", "$validHeader\nADD,d1.yml,G,ns,,YAML,")

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(listOf("1-first.csv", "2-second.csv", "10-tenth.csv"), scripts.map { it.scriptName })
    }

    // ── 4. Ignores non-.csv files and subdirectories ─────────────────────────

    @Test
    fun `ignores non-csv files and subdirectories`() {
        write("1-real.csv", "$validHeader\nADD,x.yml,G,ns,,YAML,")
        write("README.md", "some docs")
        write("notes.txt", "notes")
        tempDir.resolve("subdir").toFile().mkdirs()

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(1, scripts.size)
        assertEquals("1-real.csv", scripts[0].scriptName)
    }

    // ── 5. Throws on duplicate numeric prefixes ──────────────────────────────

    @Test
    fun `throws IllegalArgumentException on duplicate numeric prefix`() {
        write("1-alpha.csv", "$validHeader\nADD,a.yml,G,ns,,YAML,")
        write("1-beta.csv", "$validHeader\nADD,b.yml,G,ns,,YAML,")

        assertFailsWith<IllegalArgumentException> {
            loader.loadOrdered(tempDir)
        }
    }

    // ── 6. Files without numeric prefix are silently ignored ─────────────────

    @Test
    fun `silently ignores csv files without numeric prefix`() {
        write("readme.csv", "$validHeader\nADD,x.yml,G,ns,,YAML,")
        write("config.csv", "$validHeader\nADD,y.yml,G,ns,,YAML,")
        write("1-valid.csv", "$validHeader\nADD,z.yml,G,ns,,YAML,")

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(1, scripts.size)
        assertEquals("1-valid.csv", scripts[0].scriptName)
    }

    // ── 7. DELETE changeset has null content ─────────────────────────────────

    @Test
    fun `DELETE changeset has null content`() {
        write(
            "1-delete.csv",
            """
            $validHeader
            DELETE,old.yml,DEFAULT_GROUP,dev,,YAML,clean up
            """.trimIndent(),
        )

        val scripts = loader.loadOrdered(tempDir)
        val cs = scripts[0].changeSets[0]

        assertEquals(Action.DELETE, cs.action)
        assertNull(cs.content, "content should be null for DELETE")
    }

    // ── 8. Multiline content in quoted CSV field ──────────────────────────────

    @Test
    fun `multiline content in quoted csv field is parsed correctly`() {
        val csvContent = "$validHeader\n" +
            "ADD,app.yml,DEFAULT_GROUP,dev,\"line1\nline2\nline3\",YAML,multiline test"
        write("1-multiline.csv", csvContent)

        val scripts = loader.loadOrdered(tempDir)
        val cs = scripts[0].changeSets[0]

        assertEquals("line1\nline2\nline3", cs.content)
    }

    // ── 9. Empty directory returns empty list ────────────────────────────────

    @Test
    fun `empty directory returns empty list`() {
        val scripts = loader.loadOrdered(tempDir)
        assertTrue(scripts.isEmpty())
    }

    // ── 10. Gaps in numeric prefixes are allowed ─────────────────────────────

    @Test
    fun `gaps in numeric prefixes are allowed`() {
        write("1-first.csv", "$validHeader\nADD,a.yml,G,ns,,YAML,")
        write("5-fifth.csv", "$validHeader\nADD,b.yml,G,ns,,YAML,")
        write("100-hundredth.csv", "$validHeader\nADD,c.yml,G,ns,,YAML,")

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(listOf("1-first.csv", "5-fifth.csv", "100-hundredth.csv"), scripts.map { it.scriptName })
    }

    // ── 11. type null when csv cell is empty ─────────────────────────────────

    @Test
    fun `type is null when csv cell is empty`() {
        write(
            "1-no-type.csv",
            """
            $validHeader
            DELETE,old.yml,DEFAULT_GROUP,dev,,,no type
            """.trimIndent(),
        )

        val cs = loader.loadOrdered(tempDir)[0].changeSets[0]
        assertNull(cs.type)
    }

    // ── 12. Non-existent directory throws ────────────────────────────────────

    @Test
    fun `non-existent directory throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            loader.loadOrdered(tempDir.resolve("does-not-exist"))
        }
    }

    // ── utility ──────────────────────────────────────────────────────────────

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
