package com.vyrox.runtime.core.capsule

import java.util.Locale

object CapsuleFilePolicy {
    private val invalidWindowsCharacters = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun requireValidEntryName(rawName: String): String {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Enter a file or folder name." }
        require(name.length <= 120) { "File and folder names must be 120 characters or fewer." }
        require(name != "." && name != "..") { "Relative path segments are not valid names." }
        require(!name.endsWith(".") && !name.endsWith(" ")) {
            "Names cannot end with a dot or a space."
        }
        require(name.none { it in invalidWindowsCharacters || Character.isISOControl(it) }) {
            "The name contains characters that are not supported in Windows paths."
        }

        val baseName = name.substringBefore('.').uppercase(Locale.ROOT)
        require(baseName !in reservedNames) { "This name is reserved by Windows." }
        return name
    }
}
