package dev.jarvis.assistant.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Thin wrapper around llama.cpp (jarvis_llm.cpp). Every call runs on one thread: a llama context isn't thread-safe. */
class LlamaEngine private constructor(private val handle: Long) {

    /**
     * Reads [prefix] (cached after the first call, so keep it identical) and [rest], then writes up to
     * [maxTokens] tokens, constrained to [grammar] (GBNF) when given.
     */
    suspend fun generate(prefix: String, rest: String, grammar: String?, maxTokens: Int): String = coroutineScope {
        val run = async(thread) { nativeGenerate(handle, prefix, rest, grammar.orEmpty(), maxTokens) }
        try {
            String(run.await(), Charsets.UTF_8)
        } catch (e: CancellationException) {
            nativeCancel(handle) // stops the native loop at the next token
            throw e
        }
    }

    /** Timings of the last [generate], e.g. "prompt 0.4s (cached, +31 tok) · wrote 12 tok in 0.8s (15.0 tok/s)". */
    suspend fun lastStats(): String = withContext(thread) { nativeStats(handle) }

    suspend fun close() = withContext(thread) { nativeFree(handle) }

    private external fun nativeGenerate(handle: Long, prefix: String, rest: String, grammar: String, maxTokens: Int): ByteArray
    private external fun nativeStats(handle: Long): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("jarvis_llm")
        }

        private val thread = Executors.newSingleThreadExecutor { Thread(it, "jarvis-llm") }.asCoroutineDispatcher()

        /** Loads a GGUF model. 5 threads = one per fast core on the S23; more is slower (see tools/llm-bench). */
        suspend fun load(path: String, threads: Int = 5, contextSize: Int = 2048): LlamaEngine = withContext(thread) {
            val handle = nativeLoad(path, threads, contextSize)
            check(handle != 0L) { "Couldn't load the model at $path" }
            LlamaEngine(handle)
        }

        @JvmStatic
        private external fun nativeLoad(path: String, threads: Int, contextSize: Int): Long
    }
}
