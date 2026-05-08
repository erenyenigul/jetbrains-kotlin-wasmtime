@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.internal.os.OperatingSystem
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.internal.file.archive.compression.*
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsExec
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import java.io.*
import java.net.*
import java.util.Locale

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.undercouchDownload) apply false
}

buildscript {
    dependencies {
        classpath("org.tukaani:xz:1.10")
    }
}

repositories {
    mavenCentral()
}

kotlin {
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        wasmWasiTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

enum class OsName { WINDOWS, MAC, LINUX, UNKNOWN }
enum class OsArch { X86_32, X86_64, ARM64, UNKNOWN }
data class OsType(val name: OsName, val arch: OsArch)

val currentOsType = run {
    val gradleOs = OperatingSystem.current()
    val osName = when {
        gradleOs.isMacOsX  -> OsName.MAC
        gradleOs.isWindows -> OsName.WINDOWS
        gradleOs.isLinux   -> OsName.LINUX
        else               -> OsName.UNKNOWN
    }
    val osArch = when (providers.systemProperty("sun.arch.data.model").get()) {
        "32" -> OsArch.X86_32
        "64" -> when (providers.systemProperty("os.arch").get().lowercase(Locale.getDefault())) {
            "aarch64" -> OsArch.ARM64
            else      -> OsArch.X86_64
        }
        else -> OsArch.UNKNOWN
    }
    OsType(osName, osArch)
}

// Wasmtime download + unzip

val wasmtimeVersion = "40.0.0"

val wasmtimeSuffix = when (currentOsType) {
    OsType(OsName.LINUX, OsArch.X86_64)   -> "x86_64-linux"
    OsType(OsName.LINUX, OsArch.ARM64)    -> "aarch64-linux"
    OsType(OsName.MAC, OsArch.X86_64)     -> "x86_64-macos"
    OsType(OsName.MAC, OsArch.ARM64)      -> "aarch64-macos"
    OsType(OsName.WINDOWS, OsArch.X86_32),
    OsType(OsName.WINDOWS, OsArch.X86_64) -> "x86_64-windows"
    else                                  -> error("unsupported os type $currentOsType")
}

val wasmtimeArtifactName = "wasmtime-v$wasmtimeVersion-$wasmtimeSuffix"

val unzipWasmtime = run {
    val archiveType = if (currentOsType.name == OsName.WINDOWS) "zip" else "tar.xz"
    val wasmtimeArchiveName = "$wasmtimeArtifactName.$archiveType"
    val wasmtimeLocation = "https://github.com/bytecodealliance/wasmtime/releases/download/v$wasmtimeVersion/$wasmtimeArchiveName"

    val downloadedTools = File(layout.buildDirectory.asFile.get(), "tools")

    val downloadWasmtime = tasks.register("wasmtimeDownload", Download::class) {
        src(wasmtimeLocation)
        dest(File(downloadedTools, wasmtimeArchiveName))
        overwrite(false)
    }

    tasks.register("wasmtimeUnzip", Copy::class) {
        dependsOn(downloadWasmtime)
        val archive = downloadWasmtime.get().dest
        from(if (archive.extension == "zip") zipTree(archive) else tarTree(XzArchiver(archive)))
        into(downloadedTools.resolve(wasmtimeArtifactName))
    }
}

private class XzArchiver(private val file: File) : CompressedReadableResource {
    override fun read(): InputStream = org.tukaani.xz.XZInputStream(file.inputStream().buffered())
    override fun getURI(): URI = URIBuilder(file.toURI()).schemePrefix("xz:").build()
    override fun getBackingFile(): File = file
    override fun getBaseName(): String = file.name
    override fun getDisplayName(): String = file.path
}

// Wasmtime exec

fun Project.wasmtimeExecutable(): File =
    unzipWasmtime.get().destinationDir
        .resolve(wasmtimeArtifactName)
        .resolve(if (currentOsType.name == OsName.WINDOWS) "wasmtime.exe" else "wasmtime")

fun Project.createWasmtimeExec(
    nodeMjsFile: RegularFileProperty,
    taskName: String,
    taskGroup: String?,
    invokeFunction: String?,  // null = run via _start (WASI), non-null = --invoke <name>
): TaskProvider<Exec> {
    val outputDirectory = nodeMjsFile.map { it.asFile.parentFile }
    val wasmFileName = nodeMjsFile.map { "${it.asFile.nameWithoutExtension}.wasm" }

    return tasks.register(taskName, Exec::class) {
        dependsOn(unzipWasmtime)
        inputs.property("wasmFileName", wasmFileName)

        taskGroup?.let { group = it }
        description = "Executes with Wasmtime"
        executable = wasmtimeExecutable().absolutePath

        standardInput = System.`in`
        if (invokeFunction == null) {
            standardInput = System.`in`
        }

        doFirst {
            val newArgs = mutableListOf<String>()
            newArgs.add("-W")
            newArgs.add("function-references,gc,exceptions")

            if (invokeFunction != null) {
                newArgs.add("--invoke")
                newArgs.add(invokeFunction)
            }

            newArgs.add(wasmFileName.get())

            args(newArgs)
            workingDir(outputDirectory)
            environment("RUST_BACKTRACE", "full")
        }
    }
}

// Hook into test tasks — use --invoke startUnitTests (no stdin needed)

tasks.withType<KotlinJsTest>().all {
    val wasmtimeRunTask = createWasmtimeExec(
        nodeMjsFile   = inputFileProperty,
        taskName      = name.replace("Node", "Wasmtime"),
        taskGroup     = group,
        invokeFunction = "startUnitTests",
    )
    wasmtimeRunTask.configure {
        dependsOn(project.provider { this@all.taskDependencies })
    }
    tasks.withType<KotlinTestReport> {
        dependsOn(wasmtimeRunTask)
    }
}

// Hook into run tasks — WASI _start → main(), stdin works

tasks.withType<NodeJsExec>().all {
    val wasmtimeRunTask = createWasmtimeExec(
        nodeMjsFile    = inputFileProperty,
        taskName       = name.replace("Node", "Wasmtime"),
        taskGroup      = group,
        invokeFunction = null,  // run via _start, stdin inherited
    )
    wasmtimeRunTask.configure {
        dependsOn(project.provider { this@all.taskDependencies })
    }
}