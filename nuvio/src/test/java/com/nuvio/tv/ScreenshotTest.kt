package com.nuvio.tv

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws Nuvio's screens off-device and saves what they look like (build/shots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-television-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<LeXiaPluginsActivity>()

    @Test fun plugins() {
        rule.waitForIdle()
        save("plugins")
    }

    private fun save(name: String) {
        File("build/shots").mkdirs()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }
}
