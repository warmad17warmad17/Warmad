package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TOKO SUBUR", appName)
  }

  @Test
  fun `test notification preferences persistence`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = com.example.util.NotificationPreferences(context)

    prefs.isNotificationEnabled = true
    assertEquals(true, prefs.isNotificationEnabled)

    prefs.soundTitle = "Ding Tone"
    prefs.soundUri = "content://media/internal/audio/media/1"
    assertEquals("Ding Tone", prefs.soundTitle)
    assertEquals("content://media/internal/audio/media/1", prefs.soundUri)

    val sounds = com.example.util.NotificationHelper.getDeviceNotificationSounds(context)
    assertTrue(sounds.isNotEmpty())
    assertTrue(sounds.first().isDefault)
  }

  @Test
  fun `test TokoViewModel initialization succeeds`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.viewmodel.TokoViewModel(application)
    org.junit.Assert.assertNotNull(viewModel)
    org.junit.Assert.assertNotNull(viewModel.allProducts)
    org.junit.Assert.assertNotNull(viewModel.notificationPrefs)
  }

  @Test
  fun `test updateStoreName and receipt settings`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.viewmodel.TokoViewModel(application)
    viewModel.updateStoreName("TOKO SUBUR")
    viewModel.updateReceiptSettings("TOKO MAKMUR", "Jl. Baru", "0812345", "Terima kasih")
    org.junit.Assert.assertNotNull(viewModel.storeSettings)
  }

  @Test
  fun `test NoMediaHelper creates nomedia files`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val success = com.example.util.NoMediaHelper.hideAppImagesFromGallery(context)
    assertTrue(success)
    assertTrue(com.example.util.NoMediaHelper.isNoMediaProtectionActive(context))
  }
}
