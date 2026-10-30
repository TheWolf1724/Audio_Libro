package com.lectorvoz.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Migracion 1 -> 2: crea una base de datos con el esquema exportado de la version 1
 * (`schemas/.../1.json`, el que tienen los moviles ya instalados), la abre con Room v2
 * (que valida todas las tablas e indices contra las entidades) y comprueba los datos.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val schema1 = File("schemas/com.lectorvoz.core.data.db.LectorVozDatabase/1.json")

    private fun createV1(path: File) {
        val json = JSONObject(schema1.readText()).getJSONObject("database")
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = json.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val idx = e.optJSONArray("indices")
            if (idx != null) for (j in 0 until idx.length()) {
                db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = json.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL(
            "INSERT INTO books (id, uri, local_path, format, title, authors, language, cover_path, size_bytes, quick_hash, " +
                "added_at, last_opened_at, progress, locator_chapter, locator_offset, finished, favorite) VALUES " +
                "(1, 'content://a', NULL, 'EPUB', 'Uno', 'Ana', 'es', NULL, 10, 'h1', 1, 50, 1.0, 3, 4, 1, 0), " +
                "(2, 'content://b', NULL, 'PDF', 'Dos', '', NULL, NULL, 20, 'h2', 2, NULL, 0.0, 0, 0, 0, 0)",
        )
        db.execSQL("INSERT INTO collections (id, name, created_at) VALUES (1, 'Saga', 0)")
        db.execSQL("INSERT INTO book_collections (book_id, collection_id) VALUES (1, 1)")
        db.execSQL(
            "INSERT INTO pronunciation_rules (pattern, replacement, is_regex, case_sensitive, whole_word, enabled, language, position) " +
                "VALUES ('IA', 'i a', 0, 0, 1, 1, NULL, 0)",
        )
        db.version = 1
        db.close()
    }

    @Test
    fun migra1a2ConservandoDatos() = runTest {
        assertThat(schema1.exists()).isTrue()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migracion-prueba.db"
        context.deleteDatabase(name)
        createV1(context.getDatabasePath(name))

        val db = Room.databaseBuilder(context, LectorVozDatabase::class.java, name)
            .addMigrations(*LectorVozDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val uno = db.bookDao().get(1)!!
            assertThat(uno.title).isEqualTo("Uno")
            assertThat(uno.finishedAt).isEqualTo(50L) // terminado: fecha de ultima apertura
            assertThat(db.bookDao().get(2)!!.finishedAt).isNull()
            val col = db.collectionDao().get(1)!!
            assertThat(col.isSeries).isFalse()
            assertThat(db.collectionDao().refsForCollection(1).single().position).isEqualTo(0)
            assertThat(db.pronunciationRuleDao().observeGlobal().first().single().bookId).isNull()
            // La tabla nueva funciona.
            db.readingSessionDao().insert(ReadingSessionEntity(bookId = 1, kind = "READ", startedAt = 1, durationMs = 10_000, chars = 5))
            assertThat(db.readingSessionDao().observeCount().first()).isEqualTo(1)
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(2)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
