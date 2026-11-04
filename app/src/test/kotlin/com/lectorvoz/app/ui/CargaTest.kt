package com.lectorvoz.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lectorvoz.app.ui.components.LoadingHint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Aviso de carga: solo si la espera se alarga, y con un aviso extra si tarda mucho. */
@RunWith(AndroidJUnit4::class)
class CargaTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun apareceSoloSiTardaYAvisaSiSeAlarga() {
        var text by mutableStateOf<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { LoadingHint(text, slowText = "Sigue trabajando") } }
        text = "Abriendo el libro..."
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("cargando").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Abriendo el libro...").assertIsDisplayed()
        compose.onNodeWithText("Sigue trabajando").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithText("Sigue trabajando").assertIsDisplayed()
        // Termina: se va.
        text = null
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("cargando").assertDoesNotExist()
    }
}
