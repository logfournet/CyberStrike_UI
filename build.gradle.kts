import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "io.cyberstrike"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.cyberstrike.ide.jetbrains"
        name = "CyberStrike UI"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "242"
            // No until-build: keep working on future platform releases.
        }

        vendor {
            name = "CyberStrike"
            url = "https://cyberstrike.io"
        }
    }

    buildSearchableOptions = false
    instrumentCode = false
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    patchPluginXml {
        sinceBuild.set("242")
    }
    buildPlugin {
        archiveBaseName.set("CyberStrike-UI")
    }
}
