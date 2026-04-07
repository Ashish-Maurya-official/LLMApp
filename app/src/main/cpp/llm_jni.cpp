#include <jni.h>
#include <string>
#include <llama.h>
#include <android/log.h>

// Logging macros
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LLM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LLM", __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_llmapp_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {

    const char *model_path = "/storage/emulated/0/Download/gemma-2-2b-it-Q8_0.gguf";

    LOGI("===== LLM INIT START =====");
    LOGI("Model path: %s", model_path);

    // Step 1: Initialize backend
    llama_backend_init();
    LOGI("Backend initialized");

    // Step 2: Model params
    llama_model_params model_params = llama_model_default_params();

    // (Optional tweak: reduce memory pressure)
    model_params.n_gpu_layers = 0; // CPU only

    // Step 3: Load model
    LOGI("Loading model...");
    struct llama_model *model = llama_load_model_from_file(model_path, model_params);

    if (!model) {
        LOGE("❌ Model load FAILED");

        return env->NewStringUTF(
                "Model load failed ❌\n\n"
                "Possible reasons:\n"
                "1. Wrong file path\n"
                "2. No storage permission\n"
                "3. Model too large\n"
        );
    }

    LOGI("✅ Model loaded successfully");

    // Step 4: Context params
    llama_context_params ctx_params = llama_context_default_params();

    // Reduce memory usage (important for mobile)
    ctx_params.n_ctx = 512;

    // Step 5: Create context
    LOGI("Creating context...");
    struct llama_context *ctx = llama_new_context_with_model(model, ctx_params);

    if (!ctx) {
        LOGE("❌ Context creation FAILED");

        llama_free_model(model);

        return env->NewStringUTF("Context creation failed ❌");
    }

    LOGI("✅ Context created successfully");

    // Step 6: Cleanup (for now)
    llama_free(ctx);
    llama_free_model(model);

    LOGI("===== LLM INIT DONE =====");

    std::string output = "Model loaded successfully ✅";

    return env->NewStringUTF(output.c_str());
}