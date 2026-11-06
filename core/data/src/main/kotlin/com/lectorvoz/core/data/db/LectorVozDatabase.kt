package com.lectorvoz.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Base de datos de la app. Esquemas exportados en `core/data/schemas` para migrar. */
@Database(
    entities = [
        BookEntity::class,
        BookmarkEntity::class,
        CollectionEntity::class,
        BookCollectionCrossRef::class,
        PronunciationRuleEntity::class,
        ReadingSessionEntity::class,
        ReadingHistoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LectorVozDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun collectionDao(): CollectionDao
    abstract fun pronunciationRuleDao(): PronunciationRuleDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun readingHistoryDao(): ReadingHistoryDao

    companion object {
        const val NAME = "lectorvoz.db"

        /**
         * 1 -> 2: estadisticas (`reading_sessions`), series con orden (`collections.is_series`,
         * `book_collections.position`), reglas de pronunciacion por libro
         * (`pronunciation_rules.book_id`) y fecha de terminado (`books.finished_at`).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `finished_at` INTEGER")
                db.execSQL("ALTER TABLE `collections` ADD COLUMN `is_series` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `book_collections` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pronunciation_rules` ADD COLUMN `book_id` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pronunciation_rules_book_id` ON `pronunciation_rules` (`book_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`book_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
                        "`duration_ms` INTEGER NOT NULL, `chars` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_book_id` ON `reading_sessions` (`book_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_started_at` ON `reading_sessions` (`started_at`)")
                // Los libros ya terminados no tenian fecha: se usa la de ultima apertura.
                db.execSQL("UPDATE `books` SET `finished_at` = `last_opened_at` WHERE `finished` = 1")
            }
        }

        /**
         * 2 -> 3: historial de lecturas (`reading_history`), rellenado con los libros ya
         * empezados: inicio = primera sesion registrada (o la ultima apertura), fin = fecha de
         * terminado. Las miniaturas de portada las crea la app despues.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`book_id` INTEGER, `quick_hash` TEXT NOT NULL, `title` TEXT NOT NULL, `authors` TEXT NOT NULL, " +
                        "`format` TEXT NOT NULL, `cover_path` TEXT, `started_at` INTEGER NOT NULL, `finished_at` INTEGER, " +
                        "`progress` REAL NOT NULL, `updated_at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_history_quick_hash` ON `reading_history` (`quick_hash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_history_book_id` ON `reading_history` (`book_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_history_updated_at` ON `reading_history` (`updated_at`)")
                db.execSQL(
                    "INSERT OR IGNORE INTO `reading_history` (`book_id`, `quick_hash`, `title`, `authors`, `format`, `cover_path`, " +
                        "`started_at`, `finished_at`, `progress`, `updated_at`) " +
                        "SELECT b.`id`, b.`quick_hash`, b.`title`, b.`authors`, b.`format`, NULL, " +
                        // Inicio: primera sesion (o ultima apertura); nunca despues del fin.
                        "MIN(COALESCE((SELECT MIN(s.`started_at`) FROM `reading_sessions` s WHERE s.`book_id` = b.`id`), " +
                        "b.`last_opened_at`, b.`finished_at`, b.`added_at`), COALESCE(b.`finished_at`, 9223372036854775807)), " +
                        "b.`finished_at`, b.`progress`, " +
                        "MAX(COALESCE(b.`finished_at`, 0), COALESCE(b.`last_opened_at`, 0), b.`added_at`) " +
                        "FROM `books` b WHERE b.`last_opened_at` IS NOT NULL OR b.`finished` = 1",
                )
            }
        }

        /** 3 -> 4: ediciones unidas en el historial (huellas de las otras ediciones). */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reading_history` ADD COLUMN `alt_hashes` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Migraciones entre versiones. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /** Construye la base de datos en disco. */
        fun build(context: Context): LectorVozDatabase =
            Room.databaseBuilder(context.applicationContext, LectorVozDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                // Abrir una BD de esquema MAS NUEVO que el codigo (instalar un APK anterior, o
                // restaurar una copia con allowBackup) haria crashear en bucle al arrancar. Mejor
                // recrear la BD (se pierde biblioteca/progreso, recuperables) que un crash-loop.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
