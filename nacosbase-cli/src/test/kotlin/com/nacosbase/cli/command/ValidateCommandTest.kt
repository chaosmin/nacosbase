package com.nacosbase.cli.command

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidateCommandTest {

    @Test
    fun `validate exits 0 for valid scripts directory`() {
        val dir = Files.createTempDirectory("nacosbase-validate-test")
        try {
            val csv = dir.resolve("001-add.csv").toFile()
            csv.writeText(
                "action,dataId,group,namespace,content,type,description\n" +
                "ADD,redis.yml,DEFAULT_GROUP,dev,\"key: val\",YAML,test\n"
            )

            val result = ValidateCommand().test(listOf("--scripts", dir.toAbsolutePath().toString()))
            assertEquals(0, result.statusCode, "stderr: ${result.stderr}")
            assertTrue(result.output.contains("Validation passed"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate exits 1 for duplicate prefix scripts`() {
        val dir = Files.createTempDirectory("nacosbase-validate-dup-test")
        try {
            val csv1 = dir.resolve("001-add.csv").toFile()
            csv1.writeText(
                "action,dataId,group,namespace,content,type,description\n" +
                "ADD,redis.yml,DEFAULT_GROUP,dev,\"key: val\",YAML,test\n"
            )
            val csv2 = dir.resolve("001-duplicate.csv").toFile()
            csv2.writeText(
                "action,dataId,group,namespace,content,type,description\n" +
                "ADD,app.yml,DEFAULT_GROUP,dev,\"app: config\",YAML,dup\n"
            )

            val result = ValidateCommand().test(listOf("--scripts", dir.toAbsolutePath().toString()))
            assertEquals(1, result.statusCode)
            assertTrue(result.stderr.contains("Validation failed"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate exits 1 for invalid scripts directory`() {
        val result = ValidateCommand().test(listOf("--scripts", "/nonexistent/scripts/dir"))
        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Validation failed"))
    }

    @Test
    fun `validate exits 0 for empty valid directory`() {
        val dir = Files.createTempDirectory("nacosbase-validate-empty-test")
        try {
            val result = ValidateCommand().test(listOf("--scripts", dir.toAbsolutePath().toString()))
            assertEquals(0, result.statusCode, "stderr: ${result.stderr}")
            assertTrue(result.output.contains("Validation passed: 0 script(s) are valid"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate exits 1 for CSV with missing required field`() {
        val dir = Files.createTempDirectory("nacosbase-validate-bad-csv-test")
        try {
            val csv = dir.resolve("001-bad.csv").toFile()
            // Missing 'action' value
            csv.writeText(
                "action,dataId,group,namespace,content,type,description\n" +
                ",redis.yml,DEFAULT_GROUP,dev,\"key: val\",YAML,test\n"
            )

            val result = ValidateCommand().test(listOf("--scripts", dir.toAbsolutePath().toString()))
            assertEquals(1, result.statusCode)
            assertTrue(result.stderr.contains("Validation failed"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
