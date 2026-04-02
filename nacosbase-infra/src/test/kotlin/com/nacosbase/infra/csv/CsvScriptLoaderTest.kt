package com.nacosbase.infra.csv

import com.nacosbase.core.model.Action
import com.nacosbase.core.model.ConfigType
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvScriptLoaderTest {

    private val loader = CsvScriptLoader()

    @TempDir
    lateinit var tempDir: Path

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun write(name: String, content: String): File =
        tempDir.resolve(name).toFile().also { it.writeText(content) }

    private val validHeader = "action,dataId,group,namespace,key,value,type,description,operator"

    // ── 1. Loads a single valid CSV ──────────────────────────────────────────

    @Test
    fun `loads single valid csv with correct fields`() {
        write(
            "001-init-redis.csv",
            """
            $validHeader
            ADD,redis.yml,DEFAULT_GROUP,dev,server,redis,YAML,init redis,Hugo
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
        assertEquals("Hugo", cs.operator)
    }

    // ── 2. Checksum is SHA-256 of file content ───────────────────────────────

    @Test
    fun `checksum matches sha256 of raw file content`() {
        val rawContent = "$validHeader\nADD,app.yml,G,ns,key,value,YAML,desc,op"
        write("1-app.csv", rawContent)

        val scripts = loader.loadOrdered(tempDir)

        val expected = sha256(rawContent)
        assertEquals(expected, scripts[0].checksum)
    }

    // ── 3. Sorts numerically, not lexicographically ──────────────────────────

    @Test
    fun `sorts scripts by numeric prefix ascending not lexicographically`() {
        write("10-tenth.csv", "$validHeader\nADD,d10.yml,G,ns,,,YAML,,")
        write("2-second.csv", "$validHeader\nADD,d2.yml,G,ns,,,YAML,,")
        write("1-first.csv", "$validHeader\nADD,d1.yml,G,ns,,,YAML,,")

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(listOf("1-first.csv", "2-second.csv", "10-tenth.csv"), scripts.map { it.scriptName })
    }

    // ── 4. Ignores non-.csv files and subdirectories ─────────────────────────

    @Test
    fun `ignores non-csv files and subdirectories`() {
        write("1-real.csv", "$validHeader\nADD,x.yml,G,ns,,,YAML,,")
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
        write("1-alpha.csv", "$validHeader\nADD,a.yml,G,ns,,,YAML,,")
        write("1-beta.csv", "$validHeader\nADD,b.yml,G,ns,,,YAML,,")

        assertFailsWith<IllegalArgumentException> {
            loader.loadOrdered(tempDir)
        }
    }

    // ── 6. Files without numeric prefix are silently ignored ─────────────────

    @Test
    fun `silently ignores csv files without numeric prefix`() {
        write("readme.csv", "$validHeader\nADD,x.yml,G,ns,,,YAML,,")
        write("config.csv", "$validHeader\nADD,y.yml,G,ns,,,YAML,,")
        write("1-valid.csv", "$validHeader\nADD,z.yml,G,ns,,,YAML,,")

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
            DELETE,old.yml,DEFAULT_GROUP,dev,,,YAML,clean up,admin
            """.trimIndent(),
        )

        val scripts = loader.loadOrdered(tempDir)
        val cs = scripts[0].changeSets[0]

        assertEquals(Action.DELETE, cs.action)
        assertNull(cs.content, "content should be null for DELETE")
    }

    // ── 8. Multiple rows for the same config are grouped into one ChangeSet ───

    @Test
    fun `multiple rows with same dataId are grouped into one ChangeSet`() {
        val csvContent = """
            $validHeader
            ADD,app.yml,DEFAULT_GROUP,dev,host,localhost,YAML,multi-key test,system
            ADD,app.yml,DEFAULT_GROUP,dev,port,6379,YAML,multi-key test,system
            ADD,app.yml,DEFAULT_GROUP,dev,timeout,3000,YAML,multi-key test,system
        """.trimIndent()
        write("1-multikey.csv", csvContent)

        val scripts = loader.loadOrdered(tempDir)
        val changeSets = scripts[0].changeSets

        assertEquals(1, changeSets.size, "three rows for same config → one ChangeSet")
        val cs = changeSets[0]
        assertEquals("host: localhost\nport: 6379\ntimeout: 3000", cs.content)
        assertEquals("multi-key test", cs.description)
        assertEquals("system", cs.operator)
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
        write("1-first.csv", "$validHeader\nADD,a.yml,G,ns,,,YAML,,")
        write("5-fifth.csv", "$validHeader\nADD,b.yml,G,ns,,,YAML,,")
        write("100-hundredth.csv", "$validHeader\nADD,c.yml,G,ns,,,YAML,,")

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
            DELETE,old.yml,DEFAULT_GROUP,dev,,,,,
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

    // ── 13. Date-prefixed filenames (YYYYMMDD) are sorted correctly ───────────

    @Test
    fun `date-prefixed csv files are loaded and sorted in date order`() {
        write("20260331-first.csv", "$validHeader\nADD,a.yml,G,ns,k,v,YAML,desc,op")
        write("20260401-second.csv", "$validHeader\nADD,b.yml,G,ns,k,v,YAML,desc,op")

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(listOf("20260331-first.csv", "20260401-second.csv"), scripts.map { it.scriptName })
    }

    // ── 14. APPEND rows are not merged, each becomes its own ChangeSet ────────

    @Test
    fun `APPEND rows with same dataId are NOT merged - each becomes its own ChangeSet`() {
        write(
            "1-append.csv",
            """
            $validHeader
            APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,add server,hugo
            APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.2,YAML,add server,hugo
            """.trimIndent(),
        )

        val scripts = loader.loadOrdered(tempDir)
        val changeSets = scripts[0].changeSets

        assertEquals(2, changeSets.size, "APPEND rows must NOT be merged")
        assertEquals(Action.APPEND, changeSets[0].action)
        assertEquals(Action.APPEND, changeSets[1].action)
        assertEquals("192.168.1.1", changeSets[0].content)
        assertEquals("192.168.1.2", changeSets[1].content)
    }

    // ── 15. APPEND rows carry targetKey ──────────────────────────────────────

    @Test
    fun `APPEND changeset carries key as targetKey`() {
        write(
            "1-append-key.csv",
            """
            $validHeader
            APPEND,app.yml,DEFAULT_GROUP,dev,servers,192.168.1.1,YAML,add,hugo
            """.trimIndent(),
        )

        val cs = loader.loadOrdered(tempDir)[0].changeSets[0]

        assertEquals("servers", cs.targetKey)
        assertEquals("192.168.1.1", cs.content)
    }

    // ── 16. DELETE key-only (no value) has assembled content with empty value ──

    @Test
    fun `DELETE with key but no value has non-null content containing the key`() {
        write(
            "1-delete-key.csv",
            """
            $validHeader
            DELETE,app.yml,DEFAULT_GROUP,dev,timeout,,YAML,remove key,hugo
            """.trimIndent(),
        )

        val cs = loader.loadOrdered(tempDir)[0].changeSets[0]

        assertEquals(Action.DELETE, cs.action)
        // key-only DELETE: content is assembled with empty value so the engine can
        // distinguish "delete specific key" from "delete entire config" (null content)
        assertTrue(cs.content != null, "DELETE with a key should have non-null content")
        assertTrue(cs.content!!.contains("timeout"), "content should reference the key name")
    }

    // ── 17. Multiple scripts produce independent ChangeScripts ────────────────

    @Test
    fun `two csv files produce two independent ChangeScripts`() {
        write("1-first.csv", "$validHeader\nADD,a.yml,G,ns,k,v,YAML,d,op")
        write("2-second.csv", "$validHeader\nADD,b.yml,G,ns,k,v,YAML,d,op")

        val scripts = loader.loadOrdered(tempDir)

        assertEquals(2, scripts.size)
        assertNotEquals(scripts[0].checksum, scripts[1].checksum, "different files should have different checksums")
        assertEquals("a.yml", scripts[0].changeSets[0].dataId)
        assertEquals("b.yml", scripts[1].changeSets[0].dataId)
    }

    // ── 18. Different action types in same file ───────────────────────────────

    @Test
    fun `csv with mixed actions produces separate ChangeSets per config`() {
        write(
            "1-mixed.csv",
            """
            $validHeader
            ADD,new.yml,DEFAULT_GROUP,dev,host,localhost,YAML,add,hugo
            MODIFY,existing.yml,DEFAULT_GROUP,dev,port,9090,YAML,modify,hugo
            DELETE,old.yml,DEFAULT_GROUP,dev,,,YAML,delete,hugo
            """.trimIndent(),
        )

        val changeSets = loader.loadOrdered(tempDir)[0].changeSets

        assertEquals(3, changeSets.size)
        assertEquals(Action.ADD, changeSets[0].action)
        assertEquals(Action.MODIFY, changeSets[1].action)
        assertEquals(Action.DELETE, changeSets[2].action)
    }

    // ── 19. MODIFY rows with same dataId are grouped ──────────────────────────

    @Test
    fun `MODIFY rows with same dataId are grouped into one ChangeSet`() {
        write(
            "1-modify-multi.csv",
            """
            $validHeader
            MODIFY,app.yml,DEFAULT_GROUP,dev,host,newhost,YAML,update,hugo
            MODIFY,app.yml,DEFAULT_GROUP,dev,port,9090,YAML,update,hugo
            """.trimIndent(),
        )

        val changeSets = loader.loadOrdered(tempDir)[0].changeSets

        assertEquals(1, changeSets.size, "MODIFY rows for same config must be grouped")
        assertEquals(Action.MODIFY, changeSets[0].action)
        val content = changeSets[0].content!!
        assertTrue(content.contains("host"))
        assertTrue(content.contains("port"))
    }

    // ── utility ──────────────────────────────────────────────────────────────

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
