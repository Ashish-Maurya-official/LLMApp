import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.core.BaseOptions

fun main() {
    val builder = LlmInference.LlmInferenceOptions.builder()
    // We want to see if we can do builder.setBaseOptions(BaseOptions.builder().setDelegate(BaseOptions.Delegate.CPU).build())
}
