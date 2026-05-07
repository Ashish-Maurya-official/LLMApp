#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <jni.h>
#include <llama.h>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#define TAG "LLMApp_JNI"

static llama_model *model = nullptr;
static llama_context *ctx = nullptr;
static const struct llama_vocab *vocab = nullptr;
static std::mutex model_mutex;

static void call_string_method(JNIEnv *env, jobject thiz, const char *name,
                               const std::string &value) {
  jclass clazz = env->GetObjectClass(thiz);
  jmethodID method = env->GetMethodID(clazz, name, "(Ljava/lang/String;)V");
  if (!method) {
    return;
  }

  jstring jvalue = env->NewStringUTF(value.c_str());
  env->CallVoidMethod(thiz, method, jvalue);
  env->DeleteLocalRef(jvalue);
}

static long long now_ms() {
  using namespace std::chrono;
  return duration_cast<milliseconds>(steady_clock::now().time_since_epoch())
      .count();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llmapp_MainActivity_loadModel(JNIEnv *env, jobject thiz,
                                               jstring model_path_j,
                                               jint n_gpu_layers,
                                               jint context_size) {
  std::lock_guard<std::mutex> lock(model_mutex);
  const char *model_path = env->GetStringUTFChars(model_path_j, nullptr);

  llama_backend_init();

  llama_model_params model_params = llama_model_default_params();
  model_params.n_gpu_layers =
      llama_supports_gpu_offload() ? (int32_t)n_gpu_layers : 0;

  std::ostringstream loading_status;
  loading_status << "Backend: "
                 << (llama_supports_gpu_offload() ? "GPU-capable" : "CPU")
                 << " | GPU layers: " << model_params.n_gpu_layers;
  call_string_method(env, thiz, "onStatusUpdate", loading_status.str());

  model = llama_model_load_from_file(model_path, model_params);

  if (!model) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model from %s",
                        model_path);
    env->ReleaseStringUTFChars(model_path_j, model_path);
    return JNI_FALSE;
  }

  vocab = llama_model_get_vocab(model);
  llama_context_params ctx_params = llama_context_default_params();
  ctx_params.n_ctx = std::max(512, (int)context_size);
  ctx_params.n_batch = std::min(512, (int)ctx_params.n_ctx);
  ctx_params.offload_kqv = llama_supports_gpu_offload();

  ctx = llama_init_from_model(model, ctx_params);

  if (!ctx) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create context");
    llama_model_free(model);
    model = nullptr;
    env->ReleaseStringUTFChars(model_path_j, model_path);
    return JNI_FALSE;
  }

  env->ReleaseStringUTFChars(model_path_j, model_path);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llmapp_MainActivity_getBackendInfo(JNIEnv *env, jobject thiz) {
  std::lock_guard<std::mutex> lock(model_mutex);
  std::ostringstream info;
  info << (llama_supports_gpu_offload() ? "GPU offload available"
                                       : "CPU backend");
  if (ctx) {
    info << " | context: " << llama_n_ctx(ctx);
  }
  return env->NewStringUTF(info.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmapp_MainActivity_generateResponse(JNIEnv *env, jobject thiz,
                                                      jstring prompt_j,
                                                      jint max_tokens,
                                                      jfloat temperature,
                                                      jint top_k,
                                                      jfloat top_p) {
  std::unique_lock<std::mutex> lock(model_mutex);
  if (!ctx || !model) {
    call_string_method(env, thiz, "onError", "Load a model before sending a prompt.");
    return;
  }

  const long long started_at = now_ms();
  long long first_token_at = 0;
  int generated_tokens = 0;

  const char *prompt_cstr = env->GetStringUTFChars(prompt_j, nullptr);
  std::string prompt = prompt_cstr;
  env->ReleaseStringUTFChars(prompt_j, prompt_cstr);

  jclass clazz = env->GetObjectClass(thiz);
  jmethodID onTokenGenerated =
      env->GetMethodID(clazz, "onTokenGenerated", "(Ljava/lang/String;)V");
  jmethodID onComplete = env->GetMethodID(clazz, "onComplete", "()V");
  jmethodID onGenerationStats =
      env->GetMethodID(clazz, "onGenerationStats", "(Ljava/lang/String;)V");

  // Clear previous context state so it doesn't get stuck on the old prompt
  llama_memory_clear(llama_get_memory(ctx), true);

  // Tokenize
  int n_tokens_max = (int)prompt.length() + 2;
  std::vector<llama_token> tokens(n_tokens_max);
  int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.length(),
                                tokens.data(), n_tokens_max, true, true);

  if (n_tokens < 0) {
    tokens.resize(-n_tokens);
    n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.length(),
                                tokens.data(), tokens.size(), true, true);
  }
  tokens.resize(n_tokens);

  if (tokens.empty()) {
    call_string_method(env, thiz, "onError", "Prompt could not be tokenized.");
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  const int n_ctx = llama_n_ctx(ctx);
  if ((int)tokens.size() >= n_ctx) {
    call_string_method(env, thiz, "onError",
                       "Prompt is longer than the current context window.");
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  // Create batch
  llama_batch batch = llama_batch_init(512, 0, 1);

  // Fill batch
  for (int i = 0; i < (int)tokens.size(); i++) {
    batch.token[i] = tokens[i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = (i == (int)tokens.size() - 1);
  }
  batch.n_tokens = tokens.size();

  // Decode
  if (llama_decode(ctx, batch) != 0) {
    llama_batch_free(batch);
    call_string_method(env, thiz, "onError", "Prompt decode failed.");
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  llama_sampler_chain_params sampler_params =
      llama_sampler_chain_default_params();
  struct llama_sampler *smpl = llama_sampler_chain_init(sampler_params);
  llama_sampler_chain_add(smpl, llama_sampler_init_top_k(std::max(1, (int)top_k)));
  llama_sampler_chain_add(smpl, llama_sampler_init_top_p(
                                    std::min(1.0f, std::max(0.05f, top_p)), 1));
  llama_sampler_chain_add(
      smpl, llama_sampler_init_temp(std::max(0.0f, (float)temperature)));
  llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

  const int limit = std::max(1, std::min((int)max_tokens, n_ctx - (int)tokens.size() - 1));
  for (int i = 0; i < limit; i++) {
    // Check if context has been invalidated during generation
    if (!ctx || !model) {
        break;
    }

    llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

    if (llama_vocab_is_eog(vocab, new_token))
      break;

    char buf[128];
    int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
    if (n > 0) {
      std::string piece(buf, n);
      if (first_token_at == 0) {
        first_token_at = now_ms();
      }
      generated_tokens++;
      jstring token = env->NewStringUTF(piece.c_str());
      env->CallVoidMethod(thiz, onTokenGenerated, token);
      env->DeleteLocalRef(token);
    }

    batch.token[0] = new_token;
    batch.pos[0] = (int)tokens.size() + i;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = true;
    batch.n_tokens = 1;

    int decode_res = llama_decode(ctx, batch);

    if (decode_res != 0) {
      break;
    }
  }

  llama_sampler_free(smpl);
  llama_batch_free(batch);
  if (onGenerationStats) {
    const long long completed_at = now_ms();
    const long long ttft_ms =
        first_token_at > 0 ? first_token_at - started_at : completed_at - started_at;
    const double elapsed_s = std::max(0.001, (completed_at - started_at) / 1000.0);
    const double tokens_per_s = generated_tokens / elapsed_s;

    std::ostringstream stats;
    stats.setf(std::ios::fixed);
    stats.precision(2);
    stats << "TTFT " << ttft_ms << " ms | " << tokens_per_s
          << " tok/s | " << generated_tokens << " tokens";
    jstring jstats = env->NewStringUTF(stats.str().c_str());
    env->CallVoidMethod(thiz, onGenerationStats, jstats);
    env->DeleteLocalRef(jstats);
  }
  env->CallVoidMethod(thiz, onComplete);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmapp_MainActivity_unloadModel(JNIEnv *env, jobject thiz) {
  std::lock_guard<std::mutex> lock(model_mutex);
  if (ctx) {
    llama_free(ctx);
    ctx = nullptr;
  }
  if (model) {
    llama_model_free(model);
    model = nullptr;
  }
  vocab = nullptr;
  llama_backend_free();
}
