#include <android/log.h>
#include <jni.h>
#include <llama.h>
#include <mutex>
#include <string>
#include <vector>

#define TAG "LLMApp_JNI"

static llama_model *model = nullptr;
static llama_context *ctx = nullptr;
static const struct llama_vocab *vocab = nullptr;
static std::mutex model_mutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_llmapp_MainActivity_loadModel(JNIEnv *env, jobject thiz,
                                               jstring model_path_j) {
  std::lock_guard<std::mutex> lock(model_mutex);
  const char *model_path = env->GetStringUTFChars(model_path_j, nullptr);

  llama_backend_init();

  llama_model_params model_params = llama_model_default_params();
  model = llama_load_model_from_file(model_path, model_params);

  if (!model) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model from %s",
                        model_path);
    env->ReleaseStringUTFChars(model_path_j, model_path);
    return JNI_FALSE;
  }

  vocab = llama_model_get_vocab(model);
  llama_context_params ctx_params = llama_context_default_params();
  ctx_params.n_ctx = 1024;

  ctx = llama_new_context_with_model(model, ctx_params);

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

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmapp_MainActivity_generateResponse(JNIEnv *env, jobject thiz,
                                                      jstring prompt_j) {
  std::unique_lock<std::mutex> lock(model_mutex);
  if (!ctx || !model) {
    return;
  }

  const char *prompt_cstr = env->GetStringUTFChars(prompt_j, nullptr);
  std::string prompt = prompt_cstr;
  env->ReleaseStringUTFChars(prompt_j, prompt_cstr);

  jclass clazz = env->GetObjectClass(thiz);
  jmethodID onTokenGenerated =
      env->GetMethodID(clazz, "onTokenGenerated", "(Ljava/lang/String;)V");
  jmethodID onComplete = env->GetMethodID(clazz, "onComplete", "()V");

  // Clear previous context state so it doesn't get stuck on the old prompt
  llama_memory_clear(llama_get_memory(ctx), true);

  // Tokenize
  int n_tokens_max = prompt.length() + 2;
  std::vector<llama_token> tokens(n_tokens_max);
  int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.length(),
                                tokens.data(), n_tokens_max, true, true);

  if (n_tokens < 0) {
    tokens.resize(-n_tokens);
    n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.length(),
                                tokens.data(), tokens.size(), true, true);
  }
  tokens.resize(n_tokens);

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
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  struct llama_sampler *smpl = llama_sampler_init_greedy();

  for (int i = 0; i < 512; i++) {
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
      env->CallVoidMethod(thiz, onTokenGenerated,
                          env->NewStringUTF(piece.c_str()));
    }

    batch.token[0] = new_token;
    batch.pos[0] = (int)tokens.size() + i;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = true;
    batch.n_tokens = 1;

    // Release lock briefly during compute-heavy decode to allow unloadModel to acquire it if needed
    lock.unlock();
    int decode_res = llama_decode(ctx, batch);
    lock.lock();

    if (decode_res != 0) {
      break;
    }
  }

  llama_sampler_free(smpl);
  llama_batch_free(batch);
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