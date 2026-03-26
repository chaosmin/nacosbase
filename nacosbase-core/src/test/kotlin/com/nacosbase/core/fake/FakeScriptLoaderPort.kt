package com.nacosbase.core.fake

import com.nacosbase.core.model.ChangeScript
import com.nacosbase.core.port.ScriptLoaderPort
import java.nio.file.Path

class FakeScriptLoaderPort(private val scripts: List<ChangeScript> = emptyList()) : ScriptLoaderPort {
    override fun loadOrdered(scriptsDir: Path) = scripts
}
