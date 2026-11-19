// JNI bridge between LlamaEngine.kt and llama.cpp.
//
// The system prompt is the same for every request, so it's read once and kept in the cache. Qwen3.5 is a
// hybrid model whose recurrent layers can't be rewound, so, like llama-server, we save a checkpoint of just
// the recurrent state after the prompt, restore it for each request and drop the attention cache after the
// prompt. (Restoring a full-state snapshot after clearing memory produced garbage on the S23.)

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "Jarvis/LLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string prefix;
    std::vector<uint8_t> checkpoint; // recurrent state right after the prefix; empty for plain transformers
    int n_prefix = 0;
    bool prefix_was_cached = false;
    std::string last_stats;
    std::vector<llama_token_data> candidates;
    std::atomic<bool> cancelled{false};
};

using Clock = std::chrono::steady_clock;

long ms_since(Clock::time_point start) {
    return (long) std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

std::string to_string(JNIEnv * env, jstring text) {
    const char * chars = env->GetStringUTFChars(text, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(text, chars);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_bos) {
    const int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_bos, true);
    std::vector<llama_token> tokens(n);
    llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(), n, add_bos, true);
    return tokens;
}

bool decode(llama_context * ctx, std::vector<llama_token> & tokens) {
    const int n_batch = (int) llama_n_batch(ctx);
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        const int n = std::min<int>(n_batch, (int) (tokens.size() - i));
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return false;
    }
    return true;
}

// Puts the context in the state "just read [prefix]", reusing the cached prefix when possible.
bool restore_prefix(Session & s, const std::string & prefix) {
    llama_memory_t memory = llama_get_memory(s.ctx);
    s.prefix_was_cached = false;
    if (prefix == s.prefix) {
        // Checkpoint first, so the recurrent state already ends at the prefix and the trim below is clean.
        const bool restored = s.checkpoint.empty() ||
            llama_state_seq_set_data_ext(s.ctx, s.checkpoint.data(), s.checkpoint.size(), 0,
                                         LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) > 0;
        if (restored && llama_memory_seq_rm(memory, 0, s.n_prefix, -1)) {
            s.prefix_was_cached = true;
            return true;
        }
        LOGE("couldn't reuse the cached system prompt, reading it again");
    }
    llama_memory_clear(memory, true);
    const auto start = Clock::now();
    auto tokens = tokenize(s.vocab, prefix, true);
    if (!decode(s.ctx, tokens)) return false;
    s.prefix = prefix;
    s.n_prefix = (int) tokens.size();
    s.checkpoint.resize(llama_state_seq_get_size_ext(s.ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
    if (!s.checkpoint.empty()) {
        llama_state_seq_get_data_ext(s.ctx, s.checkpoint.data(), s.checkpoint.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    }
    LOGI("cached system prompt: %d tokens in %ld ms, checkpoint %zu KB", s.n_prefix, ms_since(start),
         s.checkpoint.size() / 1024);
    return true;
}

llama_sampler * make_chain() {
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return chain;
}

void load_candidates(Session & s, llama_token_data_array & array) {
    const float * logits = llama_get_logits_ith(s.ctx, -1);
    const int n_vocab = llama_vocab_n_tokens(s.vocab);
    s.candidates.resize(n_vocab);
    for (llama_token id = 0; id < n_vocab; ++id) s.candidates[id] = {id, logits[id], 0.0f};
    array = {s.candidates.data(), s.candidates.size(), -1, false};
}

// Same trick as llama.cpp's common_sampler: checking the grammar against the whole ~250k-token vocabulary
// every step is very slow, so pick a token first and only fall back to full filtering when it breaks the grammar.
llama_token sample(Session & s, llama_sampler * chain, llama_sampler * grammar) {
    llama_token_data_array array;
    load_candidates(s, array);
    llama_sampler_apply(chain, &array);
    llama_token id = array.data[array.selected].id;
    if (grammar != nullptr) {
        llama_token_data single = {id, 1.0f, 0.0f};
        llama_token_data_array single_array = {&single, 1, -1, false};
        llama_sampler_apply(grammar, &single_array);
        if (single.logit == -INFINITY) {
            load_candidates(s, array);
            llama_sampler_apply(grammar, &array);
            llama_sampler_apply(chain, &array);
            id = array.data[array.selected].id;
        }
        llama_sampler_accept(grammar, id);
    }
    llama_sampler_accept(chain, id);
    return id;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_jarvis_assistant_llm_LlamaEngine_nativeLoad(JNIEnv * env, jclass, jstring path, jint threads, jint n_ctx) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }
    const auto start = Clock::now();
    const std::string model_path = to_string(env, path);
    llama_model_params model_params = llama_model_default_params();
    // Read the weights into RAM once. Memory-mapping from shared storage goes through Android's slower file
    // layer and, under memory pressure, re-reads weights from it for every token.
    model_params.load_mode = LLAMA_LOAD_MODE_NONE;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("couldn't load %s", model_path.c_str());
        return 0;
    }
    llama_context_params params = llama_context_default_params();
    params.n_ctx = (uint32_t) n_ctx;
    params.n_batch = 512;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, params);
    if (ctx == nullptr) {
        LOGE("couldn't create a context");
        llama_model_free(model);
        return 0;
    }
    auto * session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    LOGI("loaded %s in %ld ms", model_path.c_str(), ms_since(start));
    return reinterpret_cast<jlong>(session);
}

// Reads [prefix] (cached) + [rest] and generates up to [max_tokens], constrained by [grammar] if not empty.
// Returns raw UTF-8 bytes: Java's modified UTF-8 can't hold every emoji, so Kotlin decodes them.
JNIEXPORT jbyteArray JNICALL
Java_dev_jarvis_assistant_llm_LlamaEngine_nativeGenerate(JNIEnv * env, jobject, jlong handle, jstring prefix,
                                                        jstring rest, jstring grammar, jint max_tokens) {
    auto & s = *reinterpret_cast<Session *>(handle);
    s.cancelled = false;
    std::string out;

    const auto start = Clock::now();
    if (!restore_prefix(s, to_string(env, prefix))) {
        LOGE("couldn't read the system prompt");
        return env->NewByteArray(0);
    }
    auto tokens = tokenize(s.vocab, to_string(env, rest), false);
    if (s.n_prefix + (int) tokens.size() + max_tokens > (int) llama_n_ctx(s.ctx)) {
        LOGE("request too long: %d + %zu + %d tokens", s.n_prefix, tokens.size(), max_tokens);
        return env->NewByteArray(0);
    }
    if (!tokens.empty() && !decode(s.ctx, tokens)) {
        LOGE("decode failed");
        return env->NewByteArray(0);
    }
    const long first_token_ms = ms_since(start);

    const std::string grammar_text = to_string(env, grammar);
    llama_sampler * chain = make_chain();
    llama_sampler * grammar_sampler =
        grammar_text.empty() ? nullptr : llama_sampler_init_grammar(s.vocab, grammar_text.c_str(), "root");
    int generated = 0;
    const auto gen_start = Clock::now();
    for (; generated < max_tokens && !s.cancelled; ++generated) {
        llama_token token = sample(s, chain, grammar_sampler);
        if (llama_vocab_is_eog(s.vocab, token)) break;
        char piece[256];
        const int n = llama_token_to_piece(s.vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        if (llama_decode(s.ctx, llama_batch_get_one(&token, 1)) != 0) break;
    }
    llama_sampler_free(chain);
    if (grammar_sampler != nullptr) llama_sampler_free(grammar_sampler);

    const long gen_ms = ms_since(gen_start);
    char stats[200];
    snprintf(stats, sizeof(stats), "prompt %.1fs (%s, +%zu tok) · wrote %d tok in %.1fs (%.1f tok/s)",
             first_token_ms / 1000.0, s.prefix_was_cached ? "cached" : "full", tokens.size(), generated,
             gen_ms / 1000.0, gen_ms > 0 ? generated * 1000.0 / gen_ms : 0.0);
    s.last_stats = stats;
    LOGI("%s", stats);

    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return bytes;
}

JNIEXPORT jstring JNICALL
Java_dev_jarvis_assistant_llm_LlamaEngine_nativeStats(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<Session *>(handle)->last_stats.c_str());
}

JNIEXPORT void JNICALL
Java_dev_jarvis_assistant_llm_LlamaEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Session *>(handle)->cancelled = true;
}

JNIEXPORT void JNICALL
Java_dev_jarvis_assistant_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}

}  // extern "C"
