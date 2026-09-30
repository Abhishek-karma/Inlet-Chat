plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import org.gradle.api.artifacts.ResolvedArtifact
import java.util.Properties
import java.util.zip.ZipFile

// Release signing comes from keystore.properties (gitignored, written by CI or locally);
// when the file is absent the release build stays unsigned.
val keystoreProperties = Properties()
rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { keystoreProperties.load(it) }

android {
    namespace = "com.assistant.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.inletchat.wzptbq"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProperties.isNotEmpty()) {
            create("releaseConfig") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("releaseConfig")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Required for Robolectric tests.
            isIncludeAndroidResources = true
        }
    }
}

// The licenses file is written straight into the merged assets of each variant
// rather than registered as an extra asset source dir, which would make every
// consumer of the merged assets need a dependency on the generator.
val generatedLicensesDir = layout.buildDirectory.dir("generated/licenses")

/**
 * Writes the license each dependency actually declares, so the in-app licenses
 * screen cannot drift from the shipped libraries or invent a license.
 */
val generateDependencyLicenses by tasks.registering {
    val outputDir = generatedLicensesDir
    val artifacts = configurations.named("releaseRuntimeClasspath")
    inputs.files(artifacts)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()

        fun licenseText(file: File): String? {
            if (!file.isFile) return null
            return try {
                ZipFile(file).use { zip ->
                    val candidates = zip.entries().toList().map { it.name }.filter { name ->
                        val simple = name.substringAfterLast('/').uppercase()
                        val isLicense = simple.startsWith("LICENSE") || simple.startsWith("NOTICE") || simple == "COPYING"
                        val isTopLevel = !name.contains('/') || name.substringBeforeLast('/') == "META-INF"
                        isLicense && isTopLevel
                    }
                    candidates.firstNotNullOfOrNull { name ->
                        runCatching { zip.getInputStream(zip.getEntry(name)).readBytes().decodeToString() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * The license an artifact declares in its own published POM, which
         * Gradle caches beside the artifact. Never inferred: an artifact with no
         * declaration says so rather than getting one invented for it.
         */
        fun declaredLicenses(artifact: ResolvedArtifact): Pair<List<String>, List<String>> {
            // Gradle's cache layout is <group>/<module>/<version>/<hash>/<file>,
            // and the POM sits in a sibling hash directory of the same version.
            val versionDir = artifact.file.parentFile?.parentFile ?: return Pair(emptyList(), emptyList())
            val pom = versionDir.walkTopDown()
                .maxDepth(2)
                .firstOrNull { it.isFile && it.name.endsWith(".pom") }
                ?: return Pair(emptyList(), emptyList())
            val text = runCatching { pom.readText() }.getOrNull() ?: return Pair(emptyList(), emptyList())
            val licenseBlocks = Regex("<license>(.*?)</license>", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .map { it.groupValues[1] }
                .toList()
            val names = licenseBlocks
                .mapNotNull { block ->
                    Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
                        .find(block)?.groupValues?.get(1)?.trim()
                }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            val urls = licenseBlocks
                .mapNotNull { block ->
                    Regex("<url>(.*?)</url>", RegexOption.DOT_MATCHES_ALL)
                        .find(block)?.groupValues?.get(1)?.trim()
                }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()
            return Pair(names, urls)
        }

        val sections = ArrayList<String>()
        for (artifact in artifacts.get().resolvedConfiguration.resolvedArtifacts) {
            val name: String = artifact.moduleVersion.id.toString()
            val file: File = artifact.file
            if (sections.any { it.startsWith("## $name\n") }) continue
            val licenseNames: List<String> = declaredLicenses(artifact).first
            val licenseUrls: List<String> = declaredLicenses(artifact).second
            val bundled = licenseText(file)
            sections.add(
                buildString {
                    append("## ").append(name).append("\n\n")
                    if (licenseNames.isNotEmpty()) {
                        append("License: ").append(licenseNames.joinToString(", ")).append("\n")
                    }
                    if (licenseUrls.isNotEmpty()) {
                        append(licenseUrls.joinToString(", ")).append("\n")
                    }
                    append("\n")
                    if (bundled != null) {
                        append(bundled.trim()).append("\n")
                    } else if (licenseNames.isEmpty()) {
                        append("No license declaration was found in this artifact's published metadata.\n")
                    }
                },
            )
        }
        sections.sort()
        target.resolve("licenses.txt").writeText(
            if (sections.isEmpty()) {
                "No dependencies were resolved for this build.\n"
            } else {
                sections.joinToString("\n")
            },
        )
    }
}

tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }
    .configureEach {
        dependsOn(generateDependencyLicenses)
        doLast {
            val source = generatedLicensesDir.get().asFile
            val destination = (outputs.files.files.firstOrNull() as? java.io.File)
            if (destination != null && source.isDirectory) {
                source.copyRecursively(destination, overwrite = true)
            }
        }
    }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)

    debugImplementation(libs.compose.ui.test.manifest)
}
