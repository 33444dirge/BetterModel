import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import io.papermc.paperweight.userdev.internal.setup.UserdevSetupTask
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.convention.paperweight)
}

dependencies {
    paperweight.paperDevBundle("1.21.5-R0.1-SNAPSHOT")
}

tasks {
    withType<UserdevSetupTask>().configureEach {
        launcher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
    }
    compileJava {
        options.release = 21
    }
    compileKotlin {
        compilerOptions.jvmTarget = JvmTarget.JVM_21
    }
}
