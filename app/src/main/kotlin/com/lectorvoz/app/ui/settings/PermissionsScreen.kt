package com.lectorvoz.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lectorvoz.app.R

/**
 * Permisos NECESARIOS de la app, con su proposito. La app funciona sin concederlos, pero cada uno
 * habilita una parte: notificaciones (controles de reproduccion), instalar (actualizaciones),
 * bateria (que el sistema no corte la lectura de fondo). Se muestran en la bienvenida (una vez) y
 * en Ajustes > Permisos (para concederlos mas tarde).
 */
enum class AppPermission(val title: String, val purpose: String) {
    NOTIFICATIONS(
        "Notificaciones",
        "Mostrar los controles de reproduccion y el progreso en la barra de notificaciones y en la pantalla de bloqueo mientras se escucha.",
    ),
    INSTALL_UPDATES(
        "Instalar actualizaciones",
        "Instalar las actualizaciones de la app, que se descargan de su pagina de GitHub.",
    ),
    BATTERY(
        "Bateria sin restricciones",
        "Evitar que el sistema detenga la lectura en voz alta con la pantalla apagada (algunos moviles cierran las apps en segundo plano).",
    ),
}

/** true si [p] ya esta concedido. Las versiones antiguas no requieren el de notificaciones. */
fun permissionGranted(context: Context, p: AppPermission): Boolean = when (p) {
    AppPermission.NOTIFICATIONS ->
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    AppPermission.INSTALL_UPDATES -> context.packageManager.canRequestPackageInstalls()
    AppPermission.BATTERY ->
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/** Intent de ajustes del sistema para conceder [p] (los de acceso especial); null para el runtime. */
private fun specialIntent(context: Context, p: AppPermission): Intent? = when (p) {
    AppPermission.INSTALL_UPDATES ->
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
    AppPermission.BATTERY ->
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
    AppPermission.NOTIFICATIONS -> null
}

/**
 * Lista de permisos con su proposito y un boton para conceder cada uno (o un "Concedido" si ya lo
 * esta). Reevalua el estado al volver de los ajustes del sistema (ON_RESUME). Reutilizable por la
 * bienvenida y por Ajustes > Permisos.
 */
@Composable
fun PermissionsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    Column(modifier) {
        @Suppress("UNUSED_EXPRESSION") refresh // suscribe la recomputacion de "granted" al refresco
        for (p in AppPermission.entries) {
            val granted = permissionGranted(context, p)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text(p.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (granted) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { stateDescription = "Concedido" }) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Concedido", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (p == AppPermission.NOTIFICATIONS) {
                                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                specialIntent(context, p)?.let { runCatching { context.startActivity(it) } }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Conceder") }
                }
            }
        }
    }
}

/** Ajustes > Permisos: conceder mas tarde los permisos de la app. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    SettingsScaffold("Permisos", onBack) {
        Text(
            "La app funciona sin conceder nada, pero estos permisos habilitan algunas funciones:",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        PermissionsContent(Modifier.padding(horizontal = 16.dp))
    }
}
