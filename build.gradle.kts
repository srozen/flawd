import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        val localIde = providers.gradleProperty("platformLocalPath").orNull
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        // JCEF is a separate bundled plugin since 2026.2
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = providers.gradleProperty("pluginVendorName")
            email = providers.gradleProperty("pluginVendorEmail").filter { it.isNotBlank() }
            url = providers.gradleProperty("pluginVendorUrl").filter { it.isNotBlank() }
        }
        // "What's New" on Marketplace and in the IDE, taken from this version's CHANGELOG.md section
        val changelog = project.changelog // local copy keeps the configuration cache happy
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // Secrets come from the environment and are never committed; see "Publishing" in the README
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release version such as 0.2.0-beta.1 goes to the "beta" channel; plain versions to the default one
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            // -PverifyLocalIde=<path> checks against one installed IDE instead of downloading the recommended set
            val localIde = providers.gradleProperty("verifyLocalIde").orNull
            if (localIde != null) local(file(localIde)) else recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = "https://github.com/srozen/flawd"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.JVM_25
}

// --- Web editor (bpmn-js) -------------------------------------------------------------------------
// The diagram editor is a bundled bpmn-js page; it is built with npm/esbuild and shipped as a resource.

val webDir = layout.projectDirectory.dir("web")
val webOutDir = layout.buildDirectory.dir("generated/web")
val npmCommand = providers.gradleProperty("npmCommand").getOrElse("npm")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "web"
    description = "Installs the web editor's npm dependencies."
    workingDir(webDir)
    commandLine(npmCommand, if (webDir.file("package-lock.json").asFile.exists()) "ci" else "install")
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json")).optional()
    outputs.dir(webDir.dir("node_modules"))
}

val buildWeb = tasks.register<Exec>("buildWeb") {
    group = "web"
    description = "Bundles the bpmn-js editor into a single HTML page."
    dependsOn(npmInstall)
    workingDir(webDir)
    val target = webOutDir.map { it.dir("bpmn-editor") }
    commandLine(npmCommand, "run", "build", "--", "--outdir=${target.get().asFile.absolutePath}")
    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("build.mjs"), webDir.file("package-lock.json")).optional()
    outputs.dir(webOutDir)
}

sourceSets.main {
    resources.srcDir(buildWeb)
}

tasks.jar {
    from("LICENSE") { into("META-INF") }
}

// --- Publishing guards ----------------------------------------------------------------------------

val checkReleaseMetadata = tasks.register("checkReleaseMetadata") {
    group = "intellij platform"
    description = "Fails when the Marketplace listing would go out without vendor contact details."
    val vendorEmail = providers.gradleProperty("pluginVendorEmail").orElse("")
    val vendorUrl = providers.gradleProperty("pluginVendorUrl").orElse("")
    doLast {
        check(vendorEmail.get().isNotBlank() || vendorUrl.get().isNotBlank()) {
            "Set pluginVendorEmail and/or pluginVendorUrl in gradle.properties before publishing."
        }
    }
}

tasks.publishPlugin {
    dependsOn(checkReleaseMetadata)
}

// The unified IntelliJ IDEA distribution's licensing module can't start inside the headless test framework
tasks.prepareTestSandbox {
    disabledPlugins.add("com.intellij.modules.ultimate")
}

tasks.test {
    // Chromium in the test JVM keeps it from exiting; the tests cover the no-JCEF fallback instead
    systemProperty("ide.browser.jcef.enabled", "false")
}

// verifyPluginSignature reads signPlugin's output but doesn't declare it (IntelliJ Platform Gradle Plugin 2.19)
tasks.verifyPluginSignature {
    dependsOn(tasks.signPlugin)
}
