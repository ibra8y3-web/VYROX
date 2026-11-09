package com.vyrox.runtime.core.files

import java.util.Locale

object InstallerPolicy {
    private val supportedExtensions = setOf("exe", "msi")

    fun isSupportedInstaller(displayName: String): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in supportedExtensions
    }

    fun requireSupportedInstaller(displayName: String) {
        require(isSupportedInstaller(displayName)) {
            "Choose a Windows installer ending in .exe or .msi."
        }
    }

    fun sanitizeFileName(displayName: String): String {
        val baseName = displayName
            .replace('\\', '/')
            .substringAfterLast('/')
            .map { character ->
                when {
                    character.isLetterOrDigit() -> character
                    character in "._-()" -> character
                    character.isWhitespace() -> '_'
                    else -> '_'
                }
            }
            .joinToString("")
            .trim('.', '_')
            .take(160)

        return baseName.ifBlank { "installer" }
    }
}
