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
    ],
    version = 2,
    exportSchema = true,
)
abstract class LectorVozDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun collectionDao(): CollectionDao
    abstract fun pronunciationRuleDao(): PronunciationRuleDao
    abstract fun readingSessionDao(): ReadingSessionDao

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

        /** Migraciones entre versiones. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** Construye la base de datos en disco. */
        fun build(context: Context): LectorVozDatabase =
            Room.databaseBuilder(context.applicationContext, LectorVozDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
