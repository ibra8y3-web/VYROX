package com.vyrox.runtime.core.capsule

object CapsulePolicy {
    const val MIN_NAME_LENGTH = 2
    const val MAX_NAME_LENGTH = 32

    fun normalizeName(rawName: String): String {
        val normalized = rawName.trim().replace(Regex("\\s+"), " ")
        require(normalized.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH) {
            "Capsule name must be between 2 and 32 characters."
        }
        require(normalized.all { character ->
            character.isLetterOrDigit() || character == ' ' ||
                character == '-' || character == '_'
        }) {
            "Capsule names may contain letters, numbers, spaces, hyphens, and underscores only."
        }
        return normalized
    }
}
