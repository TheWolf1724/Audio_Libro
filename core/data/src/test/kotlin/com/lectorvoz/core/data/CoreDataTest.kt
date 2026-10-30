package com.lectorvoz.core.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Comprueba que Robolectric esta bien cableado (las pruebas de Room/DataStore lo usaran). */
@RunWith(AndroidJUnit4::class)
class CoreDataTest {
    @Test
    fun robolectricDaContexto() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.packageName).isNotEmpty()
        assertThat(CoreData.MODULE).isEqualTo("core-data")
    }
}
