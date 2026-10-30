package com.lectorvoz.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lectorvoz.tts.playback.DevicePreload
import com.lectorvoz.app.AppGraph
import com.lectorvoz.app.ui.library.LibraryRoute
import com.lectorvoz.app.ui.library.LibraryViewModel
import com.lectorvoz.app.ui.reader.ReaderScreen
import com.lectorvoz.app.ui.reader.ReaderViewModel
import com.lectorvoz.app.ui.settings.AboutScreen
import com.lectorvoz.app.ui.settings.AccessibilityContent
import com.lectorvoz.app.ui.settings.DictionaryScreen
import com.lectorvoz.app.ui.settings.DictionaryViewModel
import com.lectorvoz.app.ui.settings.ExportScreen
import com.lectorvoz.app.ui.settings.ExportViewModel
import com.lectorvoz.app.ui.settings.ReadingSettingsContent
import com.lectorvoz.app.ui.settings.SettingsHome
import com.lectorvoz.app.ui.settings.SettingsRoutes
import com.lectorvoz.app.ui.settings.SettingsScaffold
import com.lectorvoz.app.ui.settings.SettingsViewModel
import com.lectorvoz.app.ui.settings.StatsScreen
import com.lectorvoz.app.ui.settings.StatsViewModel
import com.lectorvoz.app.ui.settings.VoiceSettingsContent

/** Grafo de la app para las pantallas. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("Sin AppGraph") }

/** Rutas de texto de la navegacion. */
object Routes {
    const val LIBRARY = "biblioteca"
    const val READER = "lector/{libro}"
    const val SETTINGS = "ajustes"
    fun reader(id: Long) = "lector/$id"
    fun dictionary(bookId: Long?) = SettingsRoutes.DICTIONARY + if (bookId != null) "?libro=$bookId" else ""
    fun export(bookId: Long?) = SettingsRoutes.EXPORT + if (bookId != null) "?libro=$bookId" else ""
}

/**
 * Abre el lector de [id]. Si ya se esta mostrando ese libro no hace nada; si se muestra
 * otro, lo sustituye (vuelve a la biblioteca y abre el nuevo) en vez de reutilizar la
 * entrada con `launchSingleTop`, que conservaria el ViewModel del libro anterior (A5).
 */
fun NavController.openReader(id: Long) {
    val cur = currentBackStackEntry
    if (cur?.destination?.route == Routes.READER && cur.arguments?.getLong("libro") == id) return
    navigate(Routes.reader(id)) { popUpTo(Routes.LIBRARY) }
}

/** Clave del ViewModel del lector: una instancia por libro aunque se reutilice la entrada. */
fun readerVmKey(id: Long) = "lector-$id"

/** Navegacion de la app (una sola actividad). */
@Composable
fun AppNavHost(graph: AppGraph, nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val vm: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(graph.library, graph.collections, graph.settings, onDelete = graph::deleteBook) } })
            LibraryRoute(vm, graph.covers, onOpen = { nav.openReader(it.id) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.READER, arguments = listOf(navArgument("libro") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("libro") ?: return@composable
            val vm: ReaderViewModel = viewModel(key = readerVmKey(id), factory = viewModelFactory { initializer { ReaderViewModel(id, graph) } })
            ReaderScreen(
                vm = vm,
                onBack = back,
                onOpenBook = { next -> nav.openReader(next) },
                onVoiceSettings = { nav.navigate(SettingsRoutes.VOICE) },
                onDictionary = { nav.navigate(Routes.dictionary(it)) },
                onExport = { nav.navigate(Routes.export(it)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsHome(onNavigate = { nav.navigate(it) }, onBack = back) }
        composable(SettingsRoutes.READING) {
            val vm = settingsVm(graph)
            val s by vm.state.collectAsStateWithLifecycle()
            SettingsScaffold("Lectura", back) { ReadingSettingsContent(s.reader, s.app, { vm.reader(it) }, { vm.app(it) }) }
        }
        composable(SettingsRoutes.VOICE) {
            val vm = settingsVm(graph)
            val s by vm.state.collectAsStateWithLifecycle()
            val system by vm.systemVoices.collectAsStateWithLifecycle()
            val msg by vm.message.collectAsStateWithLifecycle()
            SettingsScaffold("Voz", back) {
                val preload by vm.preload.collectAsStateWithLifecycle()
                VoiceSettingsContent(s.voice, vm.neuralVoices, system, vm.defaultVoiceId, { vm.voice(it) }, { vm.testVoice() }, { vm.loadSystemVoices() }, msg, preload)
            }
        }
        composable(SettingsRoutes.ACCESSIBILITY) {
            val vm = settingsVm(graph)
            val s by vm.state.collectAsStateWithLifecycle()
            SettingsScaffold("Accesibilidad", back) { AccessibilityContent(s.reader, s.app, vm) }
        }
        composable(
            SettingsRoutes.DICTIONARY + "?libro={libro}",
            arguments = listOf(navArgument("libro") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val id = entry.arguments?.getLong("libro")?.takeIf { it >= 0 }
            val vm: DictionaryViewModel = viewModel(factory = viewModelFactory { initializer { DictionaryViewModel(graph.pronunciation, graph.playback, graph.settings, id) } })
            DictionaryScreen(vm, back)
        }
        composable(SettingsRoutes.STATS) {
            val vm: StatsViewModel = viewModel(factory = viewModelFactory { initializer { StatsViewModel(graph.stats) } })
            StatsScreen(vm, back)
        }
        composable(
            SettingsRoutes.EXPORT + "?libro={libro}",
            arguments = listOf(navArgument("libro") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val id = entry.arguments?.getLong("libro")?.takeIf { it >= 0 }
            val vm: ExportViewModel = viewModel(key = "exportar-$id", factory = viewModelFactory { initializer { ExportViewModel(graph, id) } })
            ExportScreen(vm, back, onPickBook = { b -> nav.navigate(Routes.export(b.id)) })
        }
        composable(SettingsRoutes.ABOUT) { AboutScreen(back) }
    }
}

@Composable
private fun settingsVm(graph: AppGraph): SettingsViewModel =
    viewModel(factory = viewModelFactory {
        initializer { SettingsViewModel(graph.settings, graph.voices, graph.playback) { DevicePreload(graph.context).estimate() } }
    })
