import com.google.mediapipe.tasks.genai.llminference.LlmInference

fun test() {
    val b = LlmInference.LlmInferenceOptions.builder()
    b.setResultListener { partialResult, done -> }
}
