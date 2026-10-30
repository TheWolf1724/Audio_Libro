package com.lectorvoz.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Acceso al catalogo `libs` desde los convention plugins. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("Falta la version '$alias' en libs.versions.toml") }
        .requiredVersion

internal fun VersionCatalog.lib(alias: String) =
    findLibrary(alias).orElseThrow { IllegalStateException("Falta la libreria '$alias' en libs.versions.toml") }
