package dev.anchildress1.wildfind.core.download

import java.util.Properties

/**
 * One pinned Hugging Face file from `models.properties`.
 *
 * @property repo Hugging Face repo id
 * @property revision commit the file is pinned to
 * @property file file name inside the repo
 * @property bytes exact file size
 * @property sha256 lowercase hex SHA-256 of the file
 */
data class ModelPin(val repo: String, val revision: String, val file: String, val bytes: Long, val sha256: String) {
    /** Loads pins from the bundled `models.properties`. */
    companion object {
        /** Pins for [model] (e.g. `bioclip`); throws [IllegalArgumentException] when a key is missing. */
        fun load(model: String): ModelPin {
            val props = Properties()
            val stream = ModelPin::class.java.getResourceAsStream("/models.properties")
            requireNotNull(stream) { "models.properties missing" }.use(props::load)
            fun key(name: String) = requireNotNull(props.getProperty("$model.$name")) { "no $model.$name pin" }
            return ModelPin(key("repo"), key("revision"), key("file"), key("bytes").toLong(), key("sha256"))
        }
    }
}
