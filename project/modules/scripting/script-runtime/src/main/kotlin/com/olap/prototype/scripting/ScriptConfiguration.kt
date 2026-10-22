package com.olap.prototype.scripting

import com.olap.prototype.dsl.OlapClient
import com.olap.prototype.dsl.ScriptArguments
import com.olap.prototype.dsl.ApiDiscovery
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.*

internal fun scriptConfiguration(environment: ScriptEnvironment): ScriptCompilationConfiguration =
    ScriptCompilationConfiguration {
        displayName("Agentic OLAP Kotlin DSL")
        fileExtension("olap.kts")
        providedProperties("olap" to KotlinType(OlapClient::class), "parameters" to KotlinType(ScriptArguments::class))
        defaultImports(*ApiDiscovery.imports.toTypedArray())
        dependencies(JvmDependency(environment.classpath.map { it.toFile() }))
        compilerOptions("-language-version", "2.2", "-api-version", "2.2", "-no-stdlib", "-no-reflect")
        jvm { jvmTarget(ScriptEnvironment.JVM_TARGET) }
    }
