package com.vyrox.engine

/**
 * Read-only APT compatibility preflight for the x86-64 Wine WoW64 packages
 * inside an ARM64 Debian/Ubuntu RootFS. This never edits package sources,
 * enables foreign architectures, refreshes indexes, or installs packages.
 */
data class WinePackagePreflightResult(
    val distributionId: String,
    val versionId: String,
    val codename: String,
    val hostArchitecture: String,
    val foreignArchitectures: Set<String>,
    val wineCandidate: String,
    val wine64Candidate: String,
    val libWineCandidate: String,
    val dependencySimulationPassed: Boolean,
    val simulationWouldRemovePackages: Boolean,
    val blockers: List<String>
) {
    val preflightPassed: Boolean get() = blockers.isEmpty()

    fun summary(): String = buildString {
        append("Wine package preflight: ")
        append(if (preflightPassed) "compatible candidates found" else "blocked")
        append("\n")
        append("RootFS: ").append(distributionId).append(' ').append(versionId)
        if (codename.isNotBlank()) append(" (").append(codename).append(')')
        append("\n")
        append("Host architecture: ").append(hostArchitecture)
        append("; foreign architectures: ")
        append(foreignArchitectures.sorted().joinToString().ifBlank { "none" })
        append("\n")
        append("Candidates: wine=").append(wineCandidate)
            .append(", wine64=").append(wine64Candidate)
            .append(", libwine=").append(libWineCandidate)
        append("\n")
        append("APT dependency simulation: ")
        append(if (dependencySimulationPassed) "passed" else "failed")
        if (simulationWouldRemovePackages) append(" (would remove installed packages)")
        append("\n")
        if (blockers.isNotEmpty()) {
            blockers.forEach { append("• ").append(it).append('\n') }
        }
        append("No package was installed or changed.")
    }
}

object WinePackagePreflight {
    const val OUTPUT_BEGIN = "VYROX_WINE_PREFLIGHT_BEGIN"
    const val OUTPUT_END = "VYROX_WINE_PREFLIGHT_END"
    const val MINIMUM_WINE_MAJOR = 11

    fun createShellScript(): String = """
        set -u

        printf '%s\n' '$OUTPUT_BEGIN'

        # Never source /etc/os-release: RootFS archives are user-imported and
        # that file must be treated as data, not executable shell code.
        read_os_value() {
            awk -F= -v key="${'$'}1" '
                ${'$'}1 == key {
                    value = substr(${'$'}0, index(${'$'}0, "=") + 1)
                    sub(/^"/, "", value)
                    sub(/"${'$'}/, "", value)
                    sub(/^\047/, "", value)
                    sub(/\047${'$'}/, "", value)
                    print value
                    exit
                }
            ' /etc/os-release 2>/dev/null | tr -cd 'A-Za-z0-9._-'
        }

        OS_ID="${'$'}(read_os_value ID)"
        OS_VERSION="${'$'}(read_os_value VERSION_ID)"
        OS_CODENAME="${'$'}(read_os_value VERSION_CODENAME)"
        printf 'distribution_id=%s\n' "${'$'}{OS_ID:-unknown}"
        printf 'version_id=%s\n' "${'$'}{OS_VERSION:-unknown}"
        printf 'codename=%s\n' "${'$'}{OS_CODENAME:-unknown}"

        if ! command -v dpkg >/dev/null 2>&1 ||
           ! command -v apt-cache >/dev/null 2>&1 ||
           ! command -v apt-get >/dev/null 2>&1; then
            printf 'apt_available=false\n'
            printf '%s\n' '$OUTPUT_END'
            exit 0
        fi

        printf 'apt_available=true\n'
        HOST_ARCH="${'$'}(dpkg --print-architecture 2>/dev/null || printf unknown)"
        FOREIGN_ARCHES="${'$'}(dpkg --print-foreign-architectures 2>/dev/null | tr '\n' ',' | sed 's/,${'$'}//')"
        printf 'host_architecture=%s\n' "${'$'}{HOST_ARCH:-unknown}"
        printf 'foreign_architectures=%s\n' "${'$'}{FOREIGN_ARCHES:-}"

        package_candidate() {
            candidate="${'$'}(apt-cache policy "${'$'}1" 2>/dev/null | awk '${'$'}1 == "Candidate:" { print ${'$'}2; exit }')"
            printf '%s' "${'$'}{candidate:-(none)}"
        }

        WINE_CANDIDATE="${'$'}(package_candidate wine)"
        WINE64_CANDIDATE="${'$'}(package_candidate wine64:amd64)"
        LIBWINE_CANDIDATE="${'$'}(package_candidate libwine:amd64)"
        printf 'wine_candidate=%s\n' "${'$'}WINE_CANDIDATE"
        printf 'wine64_candidate=%s\n' "${'$'}WINE64_CANDIDATE"
        printf 'libwine_candidate=%s\n' "${'$'}LIBWINE_CANDIDATE"

        SIMULATION=''
        if SIMULATION="${'$'}(apt-get -s -o Debug::NoLocking=1 --no-install-recommends install wine libwine:amd64 2>&1)"; then
            SIMULATION_EXIT=0
        else
            SIMULATION_EXIT=${'$'}?
        fi
        WOULD_REMOVE=false
        if printf '%s\n' "${'$'}SIMULATION" | grep -Eq '^(Remv |The following packages will be REMOVED)'; then
            WOULD_REMOVE=true
        fi
        printf 'simulation_exit_code=%s\n' "${'$'}SIMULATION_EXIT"
        printf 'simulation_would_remove_packages=%s\n' "${'$'}WOULD_REMOVE"
        printf '%s\n' '$OUTPUT_END'
    """.trimIndent()

    fun parse(output: String): WinePackagePreflightResult {
        val lines = output.lineSequence().map(String::trim).toList()
        require(OUTPUT_BEGIN in lines && OUTPUT_END in lines) {
            "Wine package preflight output is incomplete."
        }
        val begin = lines.indexOf(OUTPUT_BEGIN)
        val end = lines.indexOf(OUTPUT_END)
        require(end > begin) { "Wine package preflight output markers are invalid." }

        val values = lines.subList(begin + 1, end).mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null
            else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()

        fun value(key: String, default: String = "") = values[key] ?: default
        val distributionId = value("distribution_id", "unknown").lowercase()
        val versionId = value("version_id", "unknown")
        val codename = value("codename", "unknown")
        val hostArchitecture = value("host_architecture", "unknown")
        val foreignArchitectures = value("foreign_architectures")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val wine = value("wine_candidate", "(none)")
        val wine64 = value("wine64_candidate", "(none)")
        val libWine = value("libwine_candidate", "(none)")
        val aptAvailable = value("apt_available").toBoolean()
        val simulationExitCode = value("simulation_exit_code").toIntOrNull()
        val simulationPassed = simulationExitCode == 0
        val wouldRemove = value("simulation_would_remove_packages").toBoolean()

        val blockers = buildList {
            if (!aptAvailable) add("APT and dpkg tools are not available in this RootFS.")
            if (distributionId !in setOf("debian", "ubuntu")) {
                add("Only Debian and Ubuntu RootFS images are currently supported.")
            }
            if (hostArchitecture !in setOf("arm64", "aarch64")) {
                add("The selected Linux RootFS is not ARM64.")
            }
            if ("amd64" !in foreignArchitectures) {
                add("The amd64 foreign architecture is not enabled; VYROX did not change dpkg configuration.")
            }
            if (wine == "(none)" || libWine == "(none)") {
                add("The wine wrapper or amd64 libwine candidate is missing from the existing APT indexes.")
            }
            val candidateVersions = listOf(wine, libWine).filter { it != "(none)" }.toSet()
            if (candidateVersions.size > 1) {
                add("Wine wrapper and amd64 libwine candidates do not use one matching package version.")
            }
            val wineMajor = wine.substringAfter(':').substringBefore('.').toIntOrNull()
            if (wineMajor == null || wineMajor < MINIMUM_WINE_MAJOR) {
                add("Wine " + MINIMUM_WINE_MAJOR + " or newer is required for the new WoW64 prefix mode.")
            }
            if (simulationExitCode == null || !simulationPassed) {
                add("APT could not resolve the Wine amd64 dependency set using a read-only simulation.")
            }
            if (wouldRemove) {
                add("The simulated Wine installation would remove existing RootFS packages; the operation is blocked.")
            }
        }

        return WinePackagePreflightResult(
            distributionId = distributionId,
            versionId = versionId,
            codename = codename,
            hostArchitecture = hostArchitecture,
            foreignArchitectures = foreignArchitectures,
            wineCandidate = wine,
            wine64Candidate = wine64,
            libWineCandidate = libWine,
            dependencySimulationPassed = simulationPassed,
            simulationWouldRemovePackages = wouldRemove,
            blockers = blockers
        )
    }
}
