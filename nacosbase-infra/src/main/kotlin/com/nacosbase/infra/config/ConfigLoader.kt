package com.nacosbase.infra.config

import com.charleskorn.kaml.Yaml
import java.nio.file.Path

object ConfigLoader {

    private val INTERPOLATION_REGEX = Regex("""\$\{([^}:]+)(?::-([^}]*))?\}""")

    fun load(configPath: Path = Path.of("nacosbase.yml")): Result<NacosbaseConfig> = runCatching {
        val raw = configPath.toFile().readText()
        val interpolated = interpolate(raw)
        Yaml.default.decodeFromString(NacosbaseConfig.serializer(), interpolated)
    }

    internal fun interpolate(
        input: String,
        envLookup: (String) -> String? = System::getenv,
    ): String = INTERPOLATION_REGEX.replace(input) { match ->
        val varName = match.groupValues[1]
        val hasDefault = match.value.contains(":-")
        val default = match.groupValues[2]

        envLookup(varName)
            ?: if (hasDefault) {
                default
            } else {
                throw IllegalStateException("Environment variable '$varName' is not set")
            }
    }
}
