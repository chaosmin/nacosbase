package com.nacosbase.core.validation

import com.nacosbase.core.model.ConfigType
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigValidatorTest {

    @Test fun `valid YAML passes validation`() {
        val result = ConfigValidator.validate("key: value\nport: 8080", ConfigType.YAML)
        assertTrue(result.isSuccess)
    }

    @Test fun `invalid YAML fails validation`() {
        val result = ConfigValidator.validate("key: [unclosed", ConfigType.YAML)
        assertTrue(result.isFailure)
    }

    @Test fun `valid Properties passes validation`() {
        val result = ConfigValidator.validate("timeout=3000\nhost=localhost", ConfigType.PROPERTIES)
        assertTrue(result.isSuccess)
    }

    @Test fun `invalid Properties fails validation`() {
        // A line starting with = (no key) is invalid
        val result = ConfigValidator.validate("=nokey", ConfigType.PROPERTIES)
        assertTrue(result.isFailure)
    }

    @Test fun `valid JSON passes validation`() {
        val result = ConfigValidator.validate("""{"key":"value"}""", ConfigType.JSON)
        assertTrue(result.isSuccess)
    }

    @Test fun `invalid JSON fails validation`() {
        val result = ConfigValidator.validate("{key: value}", ConfigType.JSON)
        assertTrue(result.isFailure)
    }

    @Test fun `TEXT type always passes regardless of content`() {
        val result = ConfigValidator.validate("anything goes here !!!", ConfigType.TEXT)
        assertTrue(result.isSuccess)
    }
}
