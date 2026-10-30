package com.lectorvoz.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.app.navigation.Routes
import com.lectorvoz.app.navigation.openReader
import com.lectorvoz.app.navigation.readerVmKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A5: abrir otro libro con el lector abierto muestra el libro nuevo (ViewModel por libro). */
@RunWith(AndroidJUnit4::class)
class NavegacionLectorTest {
    @get:Rule val rule = createComposeRule()

    class LibroVm(val id: Long) : ViewModel()

    @Test
    fun abrirOtroLibroDesdeElLectorCambiaDeLibro() {
        lateinit var nav: NavHostController
        val created = ArrayList<Long>()
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.LIBRARY) {
                composable(Routes.LIBRARY) { Text("biblioteca") }
                composable(Routes.READER, arguments = listOf(navArgument("libro") { type = NavType.LongType })) { e ->
                    val id = e.arguments!!.getLong("libro")
                    val vm: LibroVm = viewModel(key = readerVmKey(id)) { LibroVm(id).also { created += id } }
                    Text("lector-${vm.id}")
                }
            }
        }
        rule.runOnIdle { nav.openReader(1) }
        rule.onNodeWithText("lector-1").assertIsDisplayed()
        // "Abrir con" o la notificacion con otro libro mientras se lee el 1.
        rule.runOnIdle { nav.openReader(2) }
        rule.onNodeWithText("lector-2").assertIsDisplayed()
        // Abrir el mismo libro que se esta leyendo no lo recrea.
        rule.runOnIdle { nav.openReader(2) }
        rule.onNodeWithText("lector-2").assertIsDisplayed()
        rule.runOnIdle {
            assertThat(created).containsExactly(1L, 2L).inOrder()
            // Atras vuelve a la biblioteca, no al libro anterior.
            nav.popBackStack()
        }
        rule.onNodeWithText("biblioteca").assertIsDisplayed()
    }
}
