import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec

plugins {
    kotlin("multiplatform") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.2.1"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.kotlinmania"
version = "0.1.0"
val androidSdkDir: String? =
    providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull

if (androidSdkDir != null && file(androidSdkDir).exists()) {
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.exists()) {
        val sdkDirPropertyValue = file(androidSdkDir).absolutePath.replace("\\", "/")
        localProperties.writeText("sdk.dir=$sdkDirPropertyValue")
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // ---- Maximal Kotlin 2.3.21 target coverage ----
    //
    // Every non-JVM Kotlin target the compiler supports is declared here.
    // `jvm()` is intentionally omitted: this workspace standardizes on
    // strict-KMP (no JVM-only target — see threadlocal-kotlin for the one
    // documented exception). CodeQL Kotlin extraction is handled by the
    // dedicated `codeqlCompileJvm` JavaExec task further down this file
    // instead of a real jvm() target, because the K2 multiplatform pipeline
    // for `compileKotlinJvm` bypasses the legacy K2JVMCompiler.doExecute
    // path the CodeQL Java agent hooks.

    val xcf = XCFramework("AsyncChannel")

    // Apple desktop
    macosArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    // macosX64 was removed in Kotlin 2.3 — "Target is no longer available."

    // iOS
    iosArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    iosSimulatorArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    iosX64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }

    // tvOS
    tvosArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    tvosSimulatorArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    // tvosX64 was removed in Kotlin 2.3 — "Target is no longer available."

    // watchOS
    watchosArm32 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    watchosArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    watchosDeviceArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    watchosSimulatorArm64 {
        binaries.framework { baseName = "AsyncChannel"; xcf.add(this) }
    }
    // watchosX64 was removed in Kotlin 2.3 — "Target is no longer available."

    // Linux
    linuxX64()
    linuxArm64()

    // Windows
    mingwX64()

    // Android native (NDK targets — separate from the Android JVM library below)
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    // Web — JS (browser + nodejs runtimes on a single target)
    //
    // The asymmetric `js("jsBrowser")` + `js("jsNode")` split that the workspace
    // template once prescribed cannot satisfy Gradle 9.x's "unique attribute
    // sets" rule: the two targets emit `jsBrowserApiElements` and
    // `jsNodeApiElements` consumable configurations that share an identical
    // attribute set, and the configuration of the `:compileAndroidHostTest`
    // task fails with "Consumable configurations with identical capabilities …
    // must have unique attributes". This port has no Node-only `actual` so
    // the single-target layout is also adequate at the source level.
    js {
        browser()
        nodejs()
    }

    // Web — WasmJS
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    // Web — WasmWASI (experimental; nodejs runtime via wasi-preview1)
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    swiftExport {
        moduleName = "AsyncChannel"
        flattenPackage = "io.github.kotlinmania.asyncchannel"
    }

    android {
        namespace = "io.github.kotlinmania.asyncchannel"
        compileSdk = 34
        minSdk = 24
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
    jvmToolchain(21)
}

// Show every test event in the CI log so runs are auditable.
// Without this, gradle's default test-task output is just
// `> Task :iosSimulatorArm64Test` with no per-test PASSED/FAILED,
// which makes a green run indistinguishable from a no-op task. The
// XML/HTML reports in build/test-results/ and build/reports/tests/ still
// carry the canonical record, but those aren't visible in CI logs.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(
            TestLogEvent.STARTED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}

rootProject.extensions.configure<NodeJsEnvSpec>("kotlinNodeJsSpec") {
    version.set("24.15.0")
}

rootProject.extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") {
    version.set("24.15.0")
}

rootProject.extensions.configure<YarnRootEnvSpec>("kotlinYarnSpec") {
    version.set("1.22.22")
}

rootProject.extensions.configure<WasmYarnRootEnvSpec>("kotlinWasmYarnSpec") {
    version.set("1.22.22")
}

rootProject.extensions.configure<YarnRootExtension>("kotlinYarn") {
    resolution("diff", "8.0.3")
    resolution("**/diff", "8.0.3")
    resolution("serialize-javascript", "7.0.5")
    resolution("**/serialize-javascript", "7.0.5")
    resolution("webpack", "5.106.2")
    resolution("**/webpack", "5.106.2")
    resolution("follow-redirects", "1.16.0")
    resolution("**/follow-redirects", "1.16.0")
    resolution("lodash", "4.18.1")
    resolution("**/lodash", "4.18.1")
    resolution("ajv", "8.20.0")
    resolution("**/ajv", "8.20.0")
    resolution("brace-expansion", "5.0.5")
    resolution("**/brace-expansion", "5.0.5")
    resolution("flatted", "3.4.2")
    resolution("**/flatted", "3.4.2")
    resolution("minimatch", "10.2.5")
    resolution("**/minimatch", "10.2.5")
    resolution("picomatch", "4.0.4")
    resolution("**/picomatch", "4.0.4")
    resolution("qs", "6.15.1")
    resolution("**/qs", "6.15.1")
    resolution("socket.io-parser", "4.2.6")
    resolution("**/socket.io-parser", "4.2.6")
}


val patchedKarmaWebpackPackage = rootProject.layout.projectDirectory.dir("gradle/npm/karma-webpack").asFile.absolutePath.replace("\\", "/")

rootProject.extensions.configure<NodeJsRootExtension>("kotlinNodeJs") {
    versions.webpack.version = "5.106.2"
    versions.webpackCli.version = "7.0.2"
    versions.karma.version = "npm:karma-maintained@6.4.7"
    versions.karmaWebpack.version = "file:$patchedKarmaWebpackPackage"
    versions.mocha.version = "12.0.0-beta-10"
    versions.kotlinWebHelpers.version = "3.1.0"
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "async-channel-kotlin", version.toString())

    pom {
        name.set("async-channel-kotlin")
        description.set("Kotlin Multiplatform port of smol-rs/async-channel - Async multi-producer multi-consumer channel")
        inceptionYear.set("2026")
        url.set("https://github.com/KotlinMania/async-channel-kotlin")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("sydneyrenee")
                name.set("Sydney Renee")
                email.set("sydney@solace.ofharmony.ai")
                url.set("https://github.com/sydneyrenee")
            }
        }

        scm {
            url.set("https://github.com/KotlinMania/async-channel-kotlin")
            connection.set("scm:git:git://github.com/KotlinMania/async-channel-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/async-channel-kotlin.git")
        }
    }
}

// ---------------------------------------------------------------------------
// CodeQL Java/Kotlin extraction task
//
// The Kotlin Multiplatform build above runs on Kotlin 2.3.21. The K2 phased
// compilation pipeline (`org.jetbrains.kotlin.cli.pipeline.JvmCliPipeline`)
// is engaged whenever `-Xmulti-platform`/`-Xfragments=…` are in the kotlinc
// args — that's KGP's standard multiplatform compileKotlinJvm shape. The
// CodeQL Java agent (`codeql-java-agent.jar` v2.25.4) hooks
// `K2JVMCompiler.doExecute(…)`, which the new pipeline bypasses, so an
// agent-instrumented KMP compileKotlinJvm produces zero Kotlin TRAP.
//
// Fix: run a separate single-target JVM compile of commonMain sources via
// JavaExec with NO multiplatform flags. Without `-Xmulti-platform` /
// `-Xfragments=…` in the args, kotlinc 2.3.21 still dispatches through the
// legacy `K2JVMCompiler.doExecute` path, the agent's class-load hook fires,
// and per-source-file `*.kt.trap.gz` files get written.
//
// The agent is attached via `JAVA_TOOL_OPTIONS=-javaagent:codeql-java-agent.jar=java,kotlin`
// (set by the CI step around this task), so the JavaExec subprocess loads it
// at JVM startup independently of any LD_PRELOAD propagation chain.
//
// This task is for CodeQL extraction only. The output `.class` files are not
// published and are not part of any KMP target.

val codeqlKotlinc: Configuration by configurations.creating {
    description = "Kotlin compiler (CodeQL extraction target only — not published)"
    isCanBeResolved = true
    isCanBeConsumed = false
}

val codeqlSourceClasspath: Configuration by configurations.creating {
    description = "Runtime classpath for CodeQL extraction of commonMain sources"
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    codeqlKotlinc("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21")
    // Mirror the commonMain dependency set, pinned to the JVM artifact variant
    // since the JVM-flavoured kotlinx packages publish multiplatform metadata
    // that requires a target attribute to resolve.
    codeqlSourceClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.8.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0")
}

val codeqlCompileJvm = tasks.register<JavaExec>("codeqlCompileJvm") {
    description =
        "Compile commonMain Kotlin sources with kotlinc 2.3.21 for CodeQL Java/Kotlin extraction. " +
        "Not part of any published artifact; intended to be wrapped by `codeql database create` " +
        "or `github/codeql-action/init` so the LD_PRELOAD tracer can attach the extractor agent " +
        "to the in-process kotlinc."
    group = "verification"

    classpath(codeqlKotlinc)
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

    val outDir = layout.buildDirectory.dir("classes/kotlin/codeql-jvm")
    val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(codeqlSourceClasspath).withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(outDir)

    doFirst {
        outDir.get().asFile.mkdirs()
        args = listOf(
            "-d", outDir.get().asFile.absolutePath,
            "-classpath", codeqlSourceClasspath.asPath,
            "-jvm-target", "21",
            "-no-stdlib", // stdlib comes via the classpath
            "-no-reflect",
            "-language-version", "2.3",
            "-api-version", "2.3",
            "-opt-in", "kotlin.time.ExperimentalTime",
            "-opt-in", "kotlin.concurrent.atomics.ExperimentalAtomicApi",
            "-Xexpect-actual-classes",
        ) + sources.files.map { it.absolutePath }
    }
}
tasks.register("test") {
    group = "verification"
    description =
        "Runs the host-portable test suite (macOS + JS + WasmJS + Android unit). " +
        "Non-host native targets (mingwX64, linuxX64) only run on their own host."

    val defaultTestTasks = listOf(
        "macosArm64Test",
        "jsNodeTest",
        "wasmJsNodeTest",
        "compileAndroidMain",
        "assembleUnitTest",
    )

    dependsOn(defaultTestTasks.mapNotNull { taskName -> tasks.findByName(taskName) })
}
