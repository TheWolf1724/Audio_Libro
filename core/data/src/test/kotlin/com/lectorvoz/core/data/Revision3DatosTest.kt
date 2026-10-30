package com.lectorvoz.core.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lectorvoz.core.data.db.LectorVozDatabase
import com.lectorvoz.core.data.library.CoverExtractor
import com.lectorvoz.core.data.library.ExtractedInfo
import com.lectorvoz.core.data.library.ImportError
import com.lectorvoz.core.data.library.ImportResult
import com.lectorvoz.core.data.library.RoomLibraryRepository
import com.lectorvoz.core.data.settings.DataStoreSettingsRepository
import com.lectorvoz.core.data.settings.SettingsRanges
import com.lectorvoz.core.data.stats.RoomReadingStatsRepository
import com.lectorvoz.core.data.stats.SessionKind
import com.lectorvoz.core.model.Locator
import com.lectorvoz.core.model.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.TimeZone

/** Regresiones de la revision 3 en `:core:data` (M4, B2, B4, B6, A6, M13, B11). */
@RunWith(AndroidJUnit4::class)
class Revision3DatosTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: LectorVozDatabase

    private val extractor = CoverExtractor { _, _, _ -> ExtractedInfo(title = "T", authors = emptyList(), language = "es", coverWritten = false) }

    @Before
    fun abre() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LectorVozDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun cierra() = db.close()

    private fun repo(permission: ((Uri) -> Boolean)? = null) =
        RoomLibraryRepository(context, db.bookDao(), db.pronunciationRuleDao(), extractor, Dispatchers.Unconfined, clock = { 1000L }, database = db, persistPermission = permission)

    private fun pdf(name: String): File = tmp.newFile(name).apply { writeBytes("%PDF-1.7\nhola\n%%EOF".toByteArray()) }

    private fun register(name: String, supplier: () -> InputStream): Uri {
        val uri = Uri.parse("content://prueba/r3/$name")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { supplier() }
        return uri
    }

    /** B2: un fichero que no es un libro se rechaza tras leer la cabecera, sin copiarlo entero. */
    @Test
    fun importacionAbortaTrasLaCabecera() = runTest {
        var read = 0L
        val big = object : InputStream() {
            val total = 50L * 1024 * 1024
            override fun read(): Int = if (read >= total) -1 else { read++; 'x'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read >= total) return -1
                val n = minOf(len.toLong(), total - read).toInt()
                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                read += n
                return n
            }
        }
        val r = repo().import(register("video.pdf") { big })
        assertThat((r as ImportResult.Failed).error).isEqualTo(ImportError.UNSUPPORTED_FORMAT)
        assertThat(read).isLessThan(1024L * 1024)
        assertThat(repo().booksDir.listFiles().orEmpty().toList()).isEmpty()
    }

    /** A6: sin permiso persistente (Abrir con / Compartir) se conserva la copia privada. */
    @Test
    fun sinPermisoPersistenteSeGuardaCopia() = runTest {
        val f = pdf("temporal.pdf")
        val r = repo(permission = { false }).import(register("temporal.pdf") { FileInputStream(f) }, copy = false)
        val book = (r as ImportResult.Imported).book
        assertThat(book.localPath).isNotNull()
        assertThat(File(book.localPath!!).exists()).isTrue()
    }

    /** M13 y B4: borrar quita tambien la copia temporal de `cacheDir/abiertos` (en transaccion). */
    @Test
    fun borrarQuitaCopiaTemporalDeAbiertos() = runTest {
        val f = pdf("ref.pdf")
        val repo = repo(permission = { true })
        val book = (repo.import(register("ref.pdf") { FileInputStream(f) }, copy = false) as ImportResult.Imported).book
        assertThat(book.localPath).isNull()
        repo.openedCacheDir.mkdirs()
        val cached = File(repo.openedCacheDir, "${book.quickHash}.pdf").apply { writeText("x") }
        val other = File(repo.openedCacheDir, "otro.pdf").apply { writeText("y") }
        repo.updateProgress(book.id, Locator(1, 2), 0.5f)
        repo.delete(book.id)
        assertThat(repo.get(book.id)).isNull()
        assertThat(cached.exists()).isFalse()
        assertThat(other.exists()).isTrue()
    }

    /** M4: un error de disco no completa el flujo de ajustes ni hace fallar las escrituras. */
    @Test
    fun ajustesSobrevivenAErroresDeDisco() = runTest {
        val real = MutableStateFlow(emptyPreferences())
        var failReads = 1
        var failWrites = true
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (failReads-- > 0) throw IOException("corrupto")
                emitAll(real)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("disco lleno")
                return transform(real.value).also { real.value = it }
            }
        }
        val repo = DataStoreSettingsRepository(flaky, retryDelayMs = 1)
        // La escritura fallida no lanza (antes cerraba la app en cada toque).
        repo.updateVoice { it.copy(speed = 1.5f) }
        failWrites = false
        // Un unico recolector: tras el error recibe los valores por defecto y despues sigue vivo.
        val seen = ArrayList<Float>()
        val job = launch { repo.voice.collect { seen += it.speed } }
        advanceUntilIdle()
        assertThat(seen).containsExactly(VoiceSettings().speed)
        repo.updateVoice { it.copy(speed = 1.25f) }
        advanceUntilIdle()
        assertThat(seen.last()).isEqualTo(1.25f)
        job.cancel()
    }

    /** B6: los dias de las estadisticas son de calendario (cambio de hora) y siguen al reloj. */
    @Test
    fun estadisticasConCambioDeHora() = runTest {
        val zone = TimeZone.getTimeZone("Europe/Madrid")
        // 2026-03-30 12:00 (lunes tras el cambio de hora del domingo 29) en Madrid.
        val now = java.util.GregorianCalendar(zone).apply { clear(); set(2026, 2, 30, 12, 0) }.timeInMillis
        val stats = RoomReadingStatsRepository(db.readingSessionDao(), db.bookDao(), clock = { now }, zone = zone)
        val day29at0030 = java.util.GregorianCalendar(zone).apply { clear(); set(2026, 2, 29, 0, 30) }.timeInMillis
        val day28at2330 = java.util.GregorianCalendar(zone).apply { clear(); set(2026, 2, 28, 23, 30) }.timeInMillis
        stats.record(1, SessionKind.LISTEN, day29at0030, 60_000, 100)
        stats.record(1, SessionKind.LISTEN, day28at2330, 120_000, 100)
        val days = stats.summary.first().lastDays
        assertThat(days).hasSize(7)
        val d29 = days.first { java.util.GregorianCalendar(zone).apply { timeInMillis = it.dayStart }.get(java.util.Calendar.DAY_OF_MONTH) == 29 }
        val d28 = days.first { java.util.GregorianCalendar(zone).apply { timeInMillis = it.dayStart }.get(java.util.Calendar.DAY_OF_MONTH) == 28 }
        assertThat(d29.listenMs).isEqualTo(60_000)
        assertThat(d28.listenMs).isEqualTo(120_000)
        // Todos los inicios de dia son medianoche local.
        days.forEach { assertThat(java.util.GregorianCalendar(zone).apply { timeInMillis = it.dayStart }.get(java.util.Calendar.HOUR_OF_DAY)).isEqualTo(0) }
    }
}

