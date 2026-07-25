import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec

fun JavaToolchainService.launcherFor(jdk: ToolchainProperties): Provider<JavaLauncher> = launcherFor {
    configureToolchain(jdk)
}

fun JavaToolchainSpec.configureToolchain(jdk: ToolchainProperties?) {
    if (jdk == null) {
        return
    }
    languageVersion.set(JavaLanguageVersion.of(jdk.version))
    jdk.vendor?.takeIf { it.isNotBlank() }?.let {
        vendor.set(jvmVendorSpecFor(it))
    }
    if (jdk.implementation.equals("J9", ignoreCase = true)) {
        implementation.set(JvmImplementation.J9)
    }
}

// The CI matrix passes a short vendor token such as "eclipse". Map the known ones to Gradle's
// built-in JvmVendorSpec constants, which recognise every alias a distribution reports across
// versions; fall back to a substring match for anything unmapped. This matters for Temurin:
// JDK 8 reports java.vendor="Temurin" while 11+ report "Eclipse Adoptium", so a plain
// matching("eclipse") cannot resolve a Temurin 8 toolchain (JvmVendorSpec.matching probes the
// runtime java.vendor, not the IMPLEMENTOR field of the release file).
private fun jvmVendorSpecFor(vendor: String): JvmVendorSpec =
    when (vendor.lowercase()) {
        "eclipse", "adoptium", "temurin" -> JvmVendorSpec.ADOPTIUM
        "amazon", "corretto" -> JvmVendorSpec.AMAZON
        "azul", "zulu" -> JvmVendorSpec.AZUL
        "bellsoft", "liberica" -> JvmVendorSpec.BELLSOFT
        "microsoft" -> JvmVendorSpec.MICROSOFT
        "oracle" -> JvmVendorSpec.ORACLE
        else -> JvmVendorSpec.matching(vendor)
    }
