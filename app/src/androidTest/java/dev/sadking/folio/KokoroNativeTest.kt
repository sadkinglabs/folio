package dev.sadking.folio

import com.k2fsa.sherpa.onnx.OfflineTts
import org.junit.Test

/**
 * The natural voice's native code loads: sherpa-onnx's JNI library and ONNX Runtime are packaged
 * for this device, and the C and C++ libraries left out of the APK are not needed. It does not run
 * the voice, whose model is a separate download.
 */
class KokoroNativeTest {
    @Test
    fun nativeLibrariesLoad() {
        // Loading the class runs its static initialiser, which loads the libraries.
        Class.forName(OfflineTts::class.java.name, true, OfflineTts::class.java.classLoader)
    }
}
