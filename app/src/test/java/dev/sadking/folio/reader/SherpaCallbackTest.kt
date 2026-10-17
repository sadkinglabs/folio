package dev.sadking.folio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The callback handed to sherpa-onnx matches what its JNI code looks up: a method `Integer
 * invoke(float[])` on the callback's class (`GetMethodID(cls, "invoke",
 * "([F)Ljava/lang/Integer;")`). When the lookup fails, the native code keeps going with the error
 * pending and ART aborts the app. That was the crash on the first sentence after the continuous
 * narration change, which passed a plain lambda.
 */
class SherpaCallbackTest {
    @Test
    fun hasTheMethodSherpaOnnxLooksUp() {
        val method = SherpaCallback { true }.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
    }

    @Test
    fun aPlainLambdaDoesNotWhichIsWhyItCrashed() {
        val lambda: (FloatArray) -> Int = { 1 }
        assertThrows(NoSuchMethodException::class.java) {
            lambda.javaClass.getMethod("invoke", FloatArray::class.java)
        }
    }

    @Test
    fun keepGoingIsOneAndStopIsZero() {
        val method = SherpaCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(1, method.invoke(SherpaCallback { true }, FloatArray(4)))
        assertEquals(0, method.invoke(SherpaCallback { false }, FloatArray(4)))
    }

    @Test
    fun anExceptionStopsGenerationInsteadOfReachingNativeCode() {
        val callback = SherpaCallback { error("broken") }
        assertEquals(0, callback(FloatArray(4)))
    }
}
