package com.vyrox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinePackagePreflightTest {
    private fun output(
        distribution: String = "debian",
        hostArch: String = "arm64",
        foreign: String = "amd64",
        wine: String = "11.0-1",
        wine64: String = "11.0-1",
        libwine: String = "11.0-1",
        simulationExit: Int = 0,
        removes: Boolean = false,
        apt: Boolean = true
    ) = buildString {
        appendLine(WinePackagePreflight.OUTPUT_BEGIN)
        appendLine("distribution_id=$distribution")
        appendLine("version_id=13")
        appendLine("codename=trixie")
        appendLine("apt_available=$apt")
        appendLine("host_architecture=$hostArch")
        appendLine("foreign_architectures=$foreign")
        appendLine("wine_candidate=$wine")
        appendLine("wine64_candidate=$wine64")
        appendLine("libwine_candidate=$libwine")
        appendLine("simulation_exit_code=$simulationExit")
        appendLine("simulation_would_remove_packages=$removes")
        append(WinePackagePreflight.OUTPUT_END)
    }

    @Test
    fun acceptsMatchingDebianCandidatesAndSuccessfulReadOnlySimulation() {
        val result = WinePackagePreflight.parse(output())

        assertTrue(result.isReadyForWineInstallation)
        assertEquals("11.0-1", result.wineCandidate)
        assertEquals(setOf("amd64"), result.foreignArchitectures)
        assertTrue(result.dependencySimulationPassed)
    }

    @Test
    fun blocksWhenAmd64ForeignArchitectureIsNotEnabled() {
        val result = WinePackagePreflight.parse(output(foreign = ""))

        assertFalse(result.isReadyForWineInstallation)
        assertTrue(result.blockers.any { it.contains("amd64 foreign architecture") })
    }

    @Test
    fun blocksMissingWinePackagesOrMismatchedVersions() {
        val missing = WinePackagePreflight.parse(output(wine64 = "(none)"))
        assertFalse(missing.isReadyForWineInstallation)
        assertTrue(missing.blockers.any { it.contains("candidates are missing") })

        val mismatched = WinePackagePreflight.parse(output(libwine = "10.0-1"))
        assertFalse(mismatched.isReadyForWineInstallation)
        assertTrue(mismatched.blockers.any { it.contains("matching package version") })
    }

    @Test
    fun blocksUnsupportedDistributionAndHostArchitecture() {
        val result = WinePackagePreflight.parse(output(distribution = "alpine", hostArch = "x86_64"))

        assertFalse(result.isReadyForWineInstallation)
        assertEquals(2, result.blockers.count { it.contains("Only Debian") || it.contains("not ARM64") })
    }

    @Test
    fun blocksWineCandidatesOlderThanTheNewWow64Baseline() {
        val result = WinePackagePreflight.parse(
            output(wine = "10.0-1", wine64 = "10.0-1", libwine = "10.0-1")
        )
        assertFalse(result.isReadyForWineInstallation)
        assertTrue(result.blockers.any { it.contains("Wine 11 or newer") })
    }

    @Test
    fun blocksFailedSimulationAndPackageRemovals() {
        val failed = WinePackagePreflight.parse(output(simulationExit = 100))
        assertFalse(failed.isReadyForWineInstallation)
        assertTrue(failed.blockers.any { it.contains("read-only simulation") })

        val removing = WinePackagePreflight.parse(output(removes = true))
        assertFalse(removing.isReadyForWineInstallation)
        assertTrue(removing.blockers.any { it.contains("would remove") })
    }

    @Test
    fun rejectsOutputWithoutBeginAndEndMarkers() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WinePackagePreflight.parse("apt-cache policy wine")
        }
    }

    @Test
    fun scriptNeverSourcesRootFsOsReleaseOrChangesAptConfiguration() {
        val script = WinePackagePreflight.createShellScript()

        assertFalse(script.contains(". /etc/os-release"))
        assertFalse(script.contains("dpkg --add-architecture"))
        assertFalse(script.contains("apt-get update"))
        assertFalse(script.contains("apt-get install "))
        assertTrue(script.contains("apt-get -s"))
        assertTrue(script.contains("dpkg --print-foreign-architectures"))
        assertTrue(script.contains("apt-cache policy"))
        assertTrue(script.contains("wine64:amd64"))
        assertTrue(script.contains("libwine:amd64"))
    }
}
