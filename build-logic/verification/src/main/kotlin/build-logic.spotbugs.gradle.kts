import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import com.github.vlsi.gradle.dsl.configureEach
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
  id("com.github.spotbugs")
}

spotbugs {
    // Below statement is for Renovate Bot since it does not support toolVersion.set("..") pattern yet
    val toolVersion = "4.7.3"
    this.toolVersion.set(toolVersion)

    providers.gradleProperty("spotbugs.version")
        .takeIf { it.isPresent }
        ?.let { this.toolVersion.set(it) }
    reportLevel.set(Confidence.HIGH)
}

dependencies {
    // Parenthesis are needed here: https://github.com/gradle/gradle/issues/9248
    (constraints) {
        providers.gradleProperty("asm.version")
            .takeIf { it.isPresent }
            ?.let {
                val asmVersion = it.get()
                spotbugs("org.ow2.asm:asm:$asmVersion")
                spotbugs("org.ow2.asm:asm-all:$asmVersion")
                spotbugs("org.ow2.asm:asm-analysis:$asmVersion")
                spotbugs("org.ow2.asm:asm-commons:$asmVersion")
                spotbugs("org.ow2.asm:asm-tree:$asmVersion")
                spotbugs("org.ow2.asm:asm-util:$asmVersion")
            }
    }
}

tasks.configureEach<SpotBugsTask> {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
}
