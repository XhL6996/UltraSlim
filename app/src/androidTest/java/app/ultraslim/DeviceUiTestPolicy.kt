package app.ultraslim

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/** Physical-device display tests require explicit opt-in and the isolated verification app. */
internal fun deviceUiTestsAllowed(): Boolean {
    if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) return true
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return instrumentation.targetContext.packageName == "app.ultraslim.verifyqa" &&
        InstrumentationRegistry.getArguments().getString("allowDeviceUiTests") == "true"
}
