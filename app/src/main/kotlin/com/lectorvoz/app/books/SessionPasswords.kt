package com.lectorvoz.app.books

import java.util.concurrent.ConcurrentHashMap

/**
 * Contrasenas de PDF protegidos, SOLO en memoria y mientras viva el proceso: no se escriben en disco
 * (ni cifradas) para no guardar secretos del usuario. Al reabrir la app se vuelve a pedir la clave.
 */
class SessionPasswords {
    private val map = ConcurrentHashMap<Long, String>()

    fun get(bookId: Long): String? = map[bookId]

    fun set(bookId: Long, password: String) {
        map[bookId] = password
    }

    fun forget(bookId: Long) {
        map.remove(bookId)
    }
}
