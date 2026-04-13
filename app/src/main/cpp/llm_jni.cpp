#include <android/log.h>
#include <jni.h>
#include <llama.h>
#include <string>
#include <vector>

#define TAG "LLMApp_JNI"

extern "C" JNIEXPORT void JNICALL
Java_com_example_llmapp_MainActivity_runInference(JNIEnv *env, jobject thiz) {

  const char *model_path =
      "/storage/emulated/0/Download/gemma-2-2b-it-Q8_0.gguf";
  std::string prompt = "Hello, how are you?";

  // Get the callback method on the MainActivity instance
  jclass clazz = env->GetObjectClass(thiz);
  jmethodID onStatusUpdate =
      env->GetMethodID(clazz, "onStatusUpdate", "(Ljava/lang/String;)V");
  jmethodID onTokenGenerated =
      env->GetMethodID(clazz, "onTokenGenerated", "(Ljava/lang/String;)V");
  jmethodID onComplete = env->GetMethodID(clazz, "onComplete", "()V");

  // Status: initializing
  env->CallVoidMethod(thiz, onStatusUpdate,
                      env->NewStringUTF("Initializing llama backend..."));

  __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing llama backend...");
  llama_backend_init();

  // Status: loading model
  env->CallVoidMethod(
      thiz, onStatusUpdate,
      env->NewStringUTF("Loading model... (this may take a moment)"));

  llama_model_params model_params = llama_model_default_params();
  llama_model *model = llama_load_model_from_file(model_path, model_params);

  if (!model) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load model from %s",
                        model_path);
    env->CallVoidMethod(
        thiz, onStatusUpdate,
        env->NewStringUTF(
            "Model load failed ❌ (Check if file exists in Downloads)"));
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  env->CallVoidMethod(thiz, onStatusUpdate,
                      env->NewStringUTF("Model loaded ✅ Creating context..."));

  const struct llama_vocab *vocab = llama_model_get_vocab(model);

  llama_context_params ctx_params = llama_context_default_params();
  ctx_params.n_ctx = 512;

  llama_context *ctx = llama_new_context_with_model(model, ctx_params);

  if (!ctx) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create context");
    env->CallVoidMethod(thiz, onStatusUpdate,
                        env->NewStringUTF("Context creation failed ❌"));
    llama_model_free(model);
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  env->CallVoidMethod(thiz, onStatusUpdate,
                      env->NewStringUTF("Generating response..."));

  // Tokenize using new API
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
  for (int i = 0; i < tokens.size(); i++) {
    batch.token[i] = tokens[i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = (i == tokens.size() - 1);
  }
  batch.n_tokens = tokens.size();

  // Decode
  if (llama_decode(ctx, batch) != 0) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Initial decode failed");
    env->CallVoidMethod(thiz, onStatusUpdate,
                        env->NewStringUTF("Decode failed ❌"));
    llama_batch_free(batch);
    llama_free(ctx);
    llama_model_free(model);
    env->CallVoidMethod(thiz, onComplete);
    return;
  }

  // Sampler API
  struct llama_sampler *smpl = llama_sampler_init_greedy();

  for (int i = 0; i < 100; i++) {
    // Sample next token
    llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

    if (llama_vocab_is_eog(vocab, new_token))
      break;

    // Convert token to piece
    char buf[128];
    int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
    if (n > 0) {
      std::string piece(buf, n);
      __android_log_print(ANDROID_LOG_INFO, TAG, "Generated token: %s",
                          piece.c_str());
      // Send each token back to Kotlin immediately
      env->CallVoidMethod(thiz, onTokenGenerated,
                          env->NewStringUTF(piece.c_str()));
    }

    // Prepare next batch
    batch.token[0] = new_token;
    batch.pos[0] = tokens.size() + i;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = true;
    batch.n_tokens = 1;

    if (llama_decode(ctx, batch) != 0) {
      __android_log_print(ANDROID_LOG_ERROR, TAG, "Decode failed at step %d",
                          i);
      break;
    }
  }

  llama_sampler_free(smpl);
  llama_batch_free(batch);
  llama_free(ctx);
  llama_model_free(model);
  llama_backend_free();

  env->CallVoidMethod(thiz, onComplete);
}