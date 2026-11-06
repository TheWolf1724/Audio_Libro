package com.lectorvoz.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lectorvoz.app.ui.settings.PermissionsContent

/**
 * Pantalla de bienvenida de la PRIMERA apertura: presenta la app y sus permisos con su proposito.
 * No obliga a conceder nada (se puede hacer luego en Ajustes > Permisos). Al pulsar "Empezar" se
 * marca como vista ([onDone]) y no vuelve a salir. Ver MainActivity (se muestra segun onboardingDone).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Scaffold { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Bienvenido a LectorVoz",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Lee tus libros en PDF y EPUB como un libro y escuchalos en voz alta, con la pantalla apagada. Todo funciona en tu dispositivo, sin cuentas ni conexion.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Permisos",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "La app funciona sin conceder nada. Puedes darlos ahora o mas tarde en Ajustes > Permisos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PermissionsContent()
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Empezar") }
        }
    }
}
