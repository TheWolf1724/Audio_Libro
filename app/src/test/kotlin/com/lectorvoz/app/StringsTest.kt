package com.lectorvoz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StringsTest {
    @Test
    fun nombreDeLaApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertThat(context.getString(R.string.app_name)).isEqualTo("LectorVoz")
    }
}
