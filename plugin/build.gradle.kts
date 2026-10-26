import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationVariant
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.zip.ZipFile

val releaseKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
}

android {
    namespace = "io.nightfish.lightnovelreader.plugin.linovelib"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.nightfish.lightnovelreader.plugin.linovelib"
        minSdk = 24
        targetSdk = 36
        versionCode = 36
        versionName = "2.2.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val outputImpl = output as com.android.build.api.variant.impl.VariantOutputImpl
            outputImpl.outputFileName = outputImpl.outputFileName.get().replace(".apk", ".apk.lnrp")
        }
        variant.sources.manifests.addStaticManifestFile(
            layout.buildDirectory.file("generated/ksp/${variant.name}/resources/auto_register_manifest.xml").get().toString()
        )
        val verifyHostAbi = registerHostAbiCheck(variant)
        tasks.configureEach {
            if (name == "assemble${variant.name.replaceFirstChar(Char::uppercase)}") {
                dependsOn(verifyHostAbi)
            }
        }
    }
}

afterEvaluate {
    listOf("Debug", "Release").forEach { variant ->
        tasks.findByName("process${variant}MainManifest")?.dependsOn("ksp${variant}Kotlin")
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

// Host tests need Android Uri and the API 3 host's actual classes, not android.jar stubs.
// They are compiled here and run on a device by scripts/run-host-tests.py.
tasks.withType<Test>().configureEach {
    exclude("**/*HostTest*.class")
}

fun registerHostClassAbiCheck(variant: String) = tasks.register(
    "verify${variant.replaceFirstChar(Char::uppercase)}HostClassAbi"
) {
    val variantTitle = variant.replaceFirstChar(Char::uppercase)
    dependsOn("compile${variantTitle}Kotlin")
    doLast {
        val forbiddenMarkers = listOf(
            "kotlinx/coroutines/sync",
            "java/util/Base64",
            "j\$/util/Base64",
            "withoutPadding"
        )
        val offenders = fileTree(
            layout.buildDirectory.dir(
                "intermediates/built_in_kotlinc/$variant/compile${variantTitle}Kotlin/classes"
            )
        )
            .matching { include("**/*.class") }
            .files
            .filter { file ->
                val bytecode = String(file.readBytes(), Charsets.ISO_8859_1)
                forbiddenMarkers.any(bytecode::contains)
            }
        check(offenders.isEmpty()) {
            "Release classes link APIs unavailable in the host app: " +
                offenders.joinToString { it.name }
        }
        val webDataSourceClass = fileTree(
            layout.buildDirectory.dir(
                "intermediates/built_in_kotlinc/$variant/compile${variantTitle}Kotlin/classes"
            )
        ).matching { include("**/LinovelibWebDataSource.class") }.singleFile
        val webDataSourceBytecode = String(webDataSourceClass.readBytes(), Charsets.ISO_8859_1)
        check("io/nightfish/lightnovelreader/api/Route" !in webDataSourceBytecode) {
            "LinovelibWebDataSource directly links Route; route navigation must stay reflective for LightNovelReader 1.2.x"
        }
    }
}

fun registerHostAbiCheck(variant: ApplicationVariant): TaskProvider<Task> {
    val variantName = variant.name
    val variantTitle = variantName.replaceFirstChar(Char::uppercase)
    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val builtArtifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
    return tasks.register("verify${variantTitle}HostAbi") {
        dependsOn("verify${variantTitle}HostClassAbi")
        inputs.dir(apkDirectory)
        doLast {
            val artifacts = builtArtifactsLoader.load(apkDirectory.get())
                ?: error("No packaged ${variantName} APK metadata found for host ABI verification")
            check(artifacts.variantName == variantName) {
                "Expected ${variantName} APK metadata, found ${artifacts.variantName}"
            }
            check(artifacts.elements.isNotEmpty()) { "No packaged ${variantName} APK found for host ABI verification" }
            artifacts.elements.forEach { artifact ->
                verifyPackagedHostAbi(file(artifact.outputFile))
            }
        }
    }
}

fun verifyPackagedHostAbi(apk: File) {
    val dexContents = mutableMapOf<String, String>()
    ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.matches(Regex("classes\\d*\\.dex"))) {
                dexContents[entry.name] = zip.getInputStream(entry).use { input ->
                    String(input.readBytes(), Charsets.ISO_8859_1)
                }
            }
        }
    }
    val classMarker = "LinovelibImageStore"
    val forbiddenForClass = listOf("java/util/Base64", "j\$/util/Base64", "withoutPadding")
    val matchingDex = dexContents.filterValues { classMarker in it }
    check(matchingDex.isNotEmpty()) { "$classMarker was not found in packaged DEX files" }
    val badDex = matchingDex.filterValues { dex -> forbiddenForClass.any(dex::contains) }
    check(badDex.isEmpty()) {
        "$classMarker shares packaged DEX with unavailable host APIs: ${badDex.keys}"
    }
}

val verifyDebugHostClassAbi = registerHostClassAbiCheck("debug")
registerHostClassAbiCheck("release")
tasks.configureEach {
    if (name == "testDebugUnitTest") dependsOn(verifyDebugHostClassAbi)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    // Compile against the API 3 contract exposed by LightNovelReader 1.2.2.
    // Keeping the API on the host side avoids packaging a second copy in the plugin.
    compileOnly(libs.lightnovelreader.api)
    compileOnly(libs.androidx.navigation.runtime.ktx)
    ksp(libs.lightnovelreader.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.jsoup)
    // Tests run against the API exposed by the actual LightNovelReader 1.2.2 host.
    testImplementation(libs.lightnovelreader.api)
}
