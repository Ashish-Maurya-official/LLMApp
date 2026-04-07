package com.example.llmapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.llmapp.ui.theme.LLMAppTheme

class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("ggml")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
            System.loadLibrary("llm_jni")
        }
    }
    external fun stringFromJNI(): String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val result = stringFromJNI()   // 🔥 call C++

        setContent {
            LLMAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = result,   // 👈 pass C++ result
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LLMAppTheme {
        Greeting("Android")
    }
}