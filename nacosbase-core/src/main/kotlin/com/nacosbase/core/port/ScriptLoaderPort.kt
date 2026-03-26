package com.nacosbase.core.port

import com.nacosbase.core.model.ChangeScript
import java.nio.file.Path

interface ScriptLoaderPort {
    /**
     * Loads and returns all .csv files in [scriptsDir] sorted by numeric prefix ASC.
     * Rules:
     * - Files must be named {N}-{description}.csv where N is a non-negative integer.
     * - Duplicate numeric prefixes are an error.
     * - Non-.csv files and subdirectories are silently ignored.
     * - Numeric prefixes need not be contiguous (gaps allowed).
     */
    fun loadOrdered(scriptsDir: Path): List<ChangeScript>
}
