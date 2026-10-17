package dev.sadking.folio.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.sadking.folio.reader.narration.NarrationTuning
import dev.sadking.folio.reader.narration.ThroughputPrior
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/** Makes speech from text. Only ever called on the synthesis thread. */
internal interface Synthesizer {
    val sampleRate: Int

    /**
     * Blocking; [keepGoing] is polled between the sentences the model splits [speechText] into, so
     * a stale request stops early. Throws on failure. Returns every sample exactly once.
     */
    fun synthesize(
        speechText: String,
        sid: Int,
        speed: Float,
        silenceScale: Float,
        keepGoing: () -> Boolean,
    ): FloatArray
}

/** A loaded model: a [Synthesizer] that also owns native memory. */
internal interface LoadedVoice : Synthesizer {
    fun release()

    /**
     * For the benchmark's diagnostic only: the audio as the model's streaming callback hands it
     * over, one piece per sentence it splits the text into.
     */
    fun synthesizePieces(
        speechText: String,
        sid: Int,
        speed: Float,
        silenceScale: Float,
    ): List<FloatArray> = listOf(synthesize(speechText, sid, speed, silenceScale) { true })
}

/**
 * The streaming callback handed to sherpa-onnx: [onSamples] returns false to stop early.
 *
 * sherpa-onnx's JNI code looks the callback up by the exact method `Integer invoke(float[])`. A
 * Kotlin lambda does not have it: since Kotlin 2.0 lambdas compile to `invokedynamic`, whose class
 * has only the erased `Object invoke(Object)`. The lookup then fails, the native code carries on
 * with the error pending, and ART aborts the whole app (CheckJNI, on in debug builds). This class
 * declares the method itself, and never lets an exception reach native code.
 */
internal class SherpaCallback(private val onSamples: (FloatArray) -> Boolean) :
    (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int =
        try {
            if (onSamples(samples)) 1 else 0
        } catch (_: Throwable) {
            0
        }
}

/**
 * Kokoro through sherpa-onnx. The streaming callback is used only to stop early: the samples it
 * passes are the same ones the call returns, so reading both would play them twice.
 */
internal class SherpaSynthesizer(private val tts: OfflineTts) : LoadedVoice {
    override val sampleRate: Int = tts.sampleRate()

    override fun synthesize(
        speechText: String,
        sid: Int,
        speed: Float,
        silenceScale: Float,
        keepGoing: () -> Boolean,
    ): FloatArray =
        tts.generateWithConfigAndCallback(
                speechText,
                GenerationConfig(silenceScale = silenceScale, speed = speed, sid = sid),
                SherpaCallback { keepGoing() },
            )
            .samples

    override fun release() = tts.release()

    override fun synthesizePieces(
        speechText: String,
        sid: Int,
        speed: Float,
        silenceScale: Float,
    ): List<FloatArray> {
        val pieces = mutableListOf<FloatArray>()
        val whole =
            tts.generateWithConfigAndCallback(
                    speechText,
                    GenerationConfig(silenceScale = silenceScale, speed = speed, sid = sid),
                    SherpaCallback {
                        pieces += it.copyOf()
                        true
                    },
                )
                .samples
        // The callback and the return value carry the same samples; if they ever differ, report
        // the returned audio as one piece rather than count anything twice.
        return if (pieces.sumOf { it.size } == whole.size) pieces else listOf(whole)
    }

    companion object {
        /**
         * Every voice uses the American English pronunciation rules. The British rules ("en-gb")
         * make this model produce no audio at all; British voices keep their accent through the
         * voice.
         */
        fun load(context: Context, config: KokoroRuntime.Config): SherpaSynthesizer {
            val dir = KokoroModel.dir(context, config.variant)
            val kokoro =
                OfflineTtsKokoroModelConfig(
                    model = File(dir, config.variant.modelFile).path,
                    voices = File(dir, "voices.bin").path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                    lexicon = File(dir, "lexicon-us-en.txt").path,
                    lang = "en-us",
                )
            val model =
                OfflineTtsModelConfig(
                    kokoro = kokoro,
                    numThreads = config.threads,
                    provider = config.provider,
                )
            return SherpaSynthesizer(
                OfflineTts(
                    null,
                    OfflineTtsConfig(model = model, silenceScale = config.silenceScale),
                )
            )
        }
    }
}

/** Runs synthesis jobs one at a time on the voice's thread: what an engine holds. */
internal interface SynthesisRunner {
    val sampleRate: Int

    /**
     * Queues [job]; lower [priority] first, then lower [seq]. Skipped once [isCurrent] is false.
     */
    fun submit(
        priority: Int,
        seq: Long,
        gen: Long,
        isCurrent: () -> Boolean,
        job: (Synthesizer) -> Unit,
    )

    /** The engine is done with the voice. The model stays loaded for a while for the next one. */
    fun release()
}

/** Seconds of synthesis for seconds of audio, from one finished request. */
internal data class SynthesisObservation(
    val chars: Int,
    val synthSeconds: Double,
    val audioSeconds: Double,
)

/**
 * The one Kokoro model of the process and the one thread that uses it ("kokoro-synth"). Every
 * native call (load, generate, release) runs on that thread, so a model is never freed while it is
 * generating: a release is queued behind the request in progress. The model stays loaded across
 * engines, pauses, book and voice changes, so its 2–4 s load is paid once; it is freed only after
 * [NarrationTuning.runtimeIdleReleaseMs] with no engine, or when the app is hidden under memory
 * pressure.
 */
internal object KokoroRuntime {
    data class Config(
        val variant: KokoroModel.Variant,
        val threads: Int,
        val provider: String = "cpu",
        val silenceScale: Float,
    )

    /** An engine's hold on the loaded model. */
    class Handle
    internal constructor(
        override val sampleRate: Int,
        val config: Config,
        /** How long the model took to load for this handle; 0 when it was already loaded. */
        val loadMs: Long,
        /** The warm-up request's timing, when one ran. */
        val warmup: SynthesisObservation?,
    ) : SynthesisRunner {
        private val released = AtomicBoolean(false)

        override fun submit(
            priority: Int,
            seq: Long,
            gen: Long,
            isCurrent: () -> Boolean,
            job: (Synthesizer) -> Unit,
        ) = KokoroRuntime.submit(priority, seq, gen, isCurrent, job)

        override fun release() {
            if (released.compareAndSet(false, true)) unref()
        }
    }

    /** Builds the model; replaced in tests. Runs on the synthesis thread. */
    @Volatile
    internal var loader: (Context, Config) -> LoadedVoice = { context, config ->
        SherpaSynthesizer.load(context, config)
    }

    internal var tuning: NarrationTuning = NarrationTuning.DEFAULT

    private sealed class Task(val priority: Int, val seq: Long) : Comparable<Task> {
        val order = counter.incrementAndGet()

        override fun compareTo(other: Task): Int =
            compareValuesBy(this, other, { it.priority }, { it.seq }, { it.order })
    }

    private class Load(val context: Context, val config: Config, val warmUp: Boolean) :
        Task(Int.MIN_VALUE, 0)

    private class Run(
        priority: Int,
        seq: Long,
        val isCurrent: () -> Boolean,
        val job: (Synthesizer) -> Unit,
    ) : Task(priority, seq)

    private class Release(val ifIdleOnly: Boolean) : Task(Int.MAX_VALUE, 0)

    private val counter = AtomicLong()
    private val tasks = PriorityBlockingQueue<Task>()
    private val lock = Any()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    // Guarded by lock.
    private var thread: Thread? = null
    private var refs = 0
    private val loading = HashMap<Config, CompletableDeferred<LoadResult>>()
    private var watchingMemory = false

    // Synthesis thread only.
    private var voice: LoadedVoice? = null
    private var voiceConfig: Config? = null

    @Volatile private var warmup: SynthesisObservation? = null

    private class LoadResult(
        val sampleRate: Int,
        val loadMs: Long,
        val failure: Throwable? = null,
    )

    private val idleRelease = Runnable { enqueue(Release(ifIdleOnly = true)) }

    /** Starts loading the model now, so it is ready when the engine needs it. No-op when warm. */
    fun preload(context: Context, config: Config) {
        load(context.applicationContext, config, warmUp = true)
    }

    /** Holds the model for an engine, loading it if needed. Throws when it cannot load. */
    suspend fun acquire(context: Context, config: Config): Handle {
        val app = context.applicationContext
        synchronized(lock) {
            refs++
            main.removeCallbacks(idleRelease)
        }
        val result =
            try {
                load(app, config, warmUp = false).await()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                unref()
                throw cancel
            }
        result.failure?.let {
            unref()
            throw it
        }
        return Handle(result.sampleRate, config, result.loadMs, warmup)
    }

    /** Runs [job] on the synthesis thread; the only way to call the [Synthesizer]. */
    fun submit(
        priority: Int,
        seq: Long,
        gen: Long,
        isCurrent: () -> Boolean,
        job: (Synthesizer) -> Unit,
    ) {
        ensureThread()
        enqueue(Run(priority, seq, isCurrent, job))
    }

    /** Frees the model now if no engine holds it: the app went to the background. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && synchronized(lock) { refs == 0 })
            enqueue(Release(ifIdleOnly = true))
    }

    /** True while an engine holds the model (the benchmark then waits). */
    val inUse: Boolean
        get() = synchronized(lock) { refs > 0 }

    /** Frees an idle model at once (before the benchmark loads its own). */
    fun releaseIdle() {
        enqueue(Release(ifIdleOnly = true))
    }

    private fun load(
        app: Context,
        config: Config,
        warmUp: Boolean,
    ): CompletableDeferred<LoadResult> {
        synchronized(lock) {
            watchMemory(app)
            loading[config]?.let {
                return it
            }
            val deferred = CompletableDeferred<LoadResult>()
            loading[config] = deferred
            ensureThread()
            enqueue(Load(app, config, warmUp))
            return deferred
        }
    }

    private fun unref() {
        synchronized(lock) {
            refs = (refs - 1).coerceAtLeast(0)
            if (refs == 0) main.postDelayed(idleRelease, tuning.runtimeIdleReleaseMs)
        }
    }

    private fun watchMemory(app: Context) {
        if (watchingMemory) return
        watchingMemory = true
        app.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) = this@KokoroRuntime.onTrimMemory(level)

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
            }
        )
    }

    private fun ensureThread() {
        synchronized(lock) {
            if (thread?.isAlive == true) return
            thread =
                Thread(::loop, "kokoro-synth").apply {
                    isDaemon = true
                    start()
                }
        }
    }

    private val outstanding = java.util.concurrent.atomic.AtomicInteger()

    /** Tasks queued or running on the synthesis thread (tests wait for 0). */
    internal val pendingTasks: Int
        get() = outstanding.get()

    private fun enqueue(task: Task) {
        outstanding.incrementAndGet()
        tasks.add(task)
    }

    private fun loop() {
        while (true) {
            val task = tasks.take()
            try {
                when (task) {
                    is Load -> runLoad(task)
                    is Run -> runJob(task)
                    is Release -> runRelease(task)
                }
            } catch (_: Throwable) {
                // An uncaught exception here would end the app, not just narration.
            } finally {
                outstanding.decrementAndGet()
            }
        }
    }

    private fun runLoad(task: Load) {
        val result =
            try {
                val started = SystemClock.elapsedRealtime()
                var loadMs = 0L
                if (voiceConfig != task.config || voice == null) {
                    val old = voice
                    voice = null
                    // A model that fails to free must not stop the next one from loading.
                    try {
                        old?.release()
                    } catch (_: Throwable) {}
                    voiceConfig = null
                    warmup = null
                    voice = loader(task.context, task.config)
                    voiceConfig = task.config
                    loadMs = SystemClock.elapsedRealtime() - started
                }
                LoadResult(voice!!.sampleRate, loadMs)
            } catch (failure: Throwable) {
                // UnsatisfiedLinkError included: a missing native library must not crash.
                voice = null
                voiceConfig = null
                LoadResult(0, 0, failure)
            }
        val (deferred, idle) =
            synchronized(lock) { loading.remove(task.config) to (refs == 0 && loading.isEmpty()) }
        deferred?.complete(result)
        val loaded = voice ?: return
        // Warm up only when nobody waits: a waiting request is a better warm-up than a dummy one,
        // and it must not queue behind it.
        if (task.warmUp && idle && warmup == null && tasks.none { it is Run }) {
            warmup = warmUp(loaded, task.config)
            synchronized(lock) {
                if (refs == 0) main.postDelayed(idleRelease, tuning.runtimeIdleReleaseMs)
            }
        }
    }

    private fun warmUp(voice: LoadedVoice, config: Config): SynthesisObservation? =
        try {
            val started = System.nanoTime()
            val samples =
                voice.synthesize(WARM_UP, KOKORO_DEFAULT_SPEAKER, 1f, config.silenceScale) { true }
            SynthesisObservation(
                WARM_UP.length,
                (System.nanoTime() - started) / 1e9,
                samples.size.toDouble() / voice.sampleRate,
            )
        } catch (_: Throwable) {
            null
        }

    private fun runJob(task: Run) {
        if (!task.isCurrent()) return
        val current = voice ?: NotLoaded
        try {
            task.job(current)
        } catch (_: Throwable) {
            // A job reports its own failures; one that throws must not end the thread.
        }
    }

    private fun runRelease(task: Release) {
        if (task.ifIdleOnly && synchronized(lock) { refs > 0 || loading.isNotEmpty() }) return
        val loaded = voice
        voice = null
        voiceConfig = null
        warmup = null
        loaded?.release()
    }

    private object NotLoaded : Synthesizer {
        override val sampleRate = 0

        override fun synthesize(
            speechText: String,
            sid: Int,
            speed: Float,
            silenceScale: Float,
            keepGoing: () -> Boolean,
        ): FloatArray = throw IllegalStateException("The natural voice is not loaded")
    }

    private const val WARM_UP = "Ready."

    /** The thread count to use: the benchmark's best, else 4 (the Tensor G4's big cores + 1). */
    fun threads(context: Context, variant: KokoroModel.Variant): Int =
        NarrationPriors.bestThreads(context, variant)
            ?: Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** The configuration for [variant] on this phone. */
    fun configFor(context: Context, variant: KokoroModel.Variant) =
        Config(variant, threads(context, variant), silenceScale = tuning.silenceScale)

    /**
     * The variant to speak with: [override] when installed; else the installed one with the best
     * measured pace; with no measurements, the faster package if installed.
     */
    fun chooseVariant(context: Context, override: KokoroModel.Variant?): KokoroModel.Variant {
        val installed = KokoroModel.installed(context)
        if (override != null && override in installed) return override
        val measured = installed.mapNotNull { variant ->
            NarrationPriors.load(context, variant, threads(context, variant))
                .takeIf { it.n > 0 }
                ?.let { variant to NarrationPriors.ratio(it) }
        }
        measured
            .minByOrNull { it.second }
            ?.let {
                return it.first
            }
        return if (KokoroModel.Variant.FP32 in installed) KokoroModel.Variant.FP32
        else KokoroModel.Variant.INT8
    }
}

/**
 * How fast Kokoro runs on this phone, per variant and thread count, saved between sessions in
 * `narration_priors`: the benchmark writes it and every listening session refines it. Numbers only.
 */
internal object NarrationPriors {
    private const val FILE = "narration_priors"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(variant: KokoroModel.Variant, threads: Int) =
        "${variant.name.lowercase()}:$threads"

    fun default(variant: KokoroModel.Variant) =
        when (variant) {
            KokoroModel.Variant.INT8 -> ThroughputPrior.INT8_DEFAULT
            KokoroModel.Variant.FP32 -> ThroughputPrior.FP32_DEFAULT
        }

    /**
     * The saved prior as measured. [ThroughputModel] widens its spread by half until the session
     * has measured enough of its own; widening here too would compound on every save.
     */
    fun load(context: Context, variant: KokoroModel.Variant, threads: Int): ThroughputPrior {
        val prefs = prefs(context)
        val k = key(variant, threads)
        if (!prefs.contains("$k:a")) return default(variant)
        return ThroughputPrior(
            a = prefs.getFloat("$k:a", 0f).toDouble(),
            b1 = prefs.getFloat("$k:b1", 0f).toDouble(),
            alpha1 = prefs.getFloat("$k:alpha1", 0f).toDouble(),
            cv = prefs.getFloat("$k:cv", 0.25f).toDouble(),
            n = prefs.getInt("$k:n", 0),
            updatedAtMs = prefs.getLong("$k:at", 0),
        )
    }

    fun save(
        context: Context,
        variant: KokoroModel.Variant,
        threads: Int,
        prior: ThroughputPrior,
    ) {
        if (prior.b1 <= 0 || prior.alpha1 <= 0) return
        val k = key(variant, threads)
        prefs(context)
            .edit()
            .putFloat("$k:a", prior.a.toFloat())
            .putFloat("$k:b1", prior.b1.toFloat())
            .putFloat("$k:alpha1", prior.alpha1.toFloat())
            .putFloat("$k:cv", prior.cv.toFloat())
            .putInt("$k:n", prior.n)
            .putLong("$k:at", prior.updatedAtMs)
            .apply()
    }

    fun bestThreads(context: Context, variant: KokoroModel.Variant): Int? =
        prefs(context).getInt("${variant.name.lowercase()}:bestThreads", 0).takeIf { it > 0 }

    fun saveBestThreads(context: Context, variant: KokoroModel.Variant, threads: Int) =
        prefs(context).edit().putInt("${variant.name.lowercase()}:bestThreads", threads).apply()

    /** Synthesis seconds per audio second for a typical 4 s sentence under [prior]. */
    fun ratio(prior: ThroughputPrior): Double {
        val chars = 4.0 / prior.alpha1
        return (prior.a + prior.b1 * chars) / (prior.alpha1 * chars)
    }
}
