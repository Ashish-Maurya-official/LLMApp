# LLMApp - Local Android LLM Setup Guide

This guide provides detailed instructions to set up, build, and run the LLM application on your local machine and Android device.

---

## 💻 System Setup

### 🍏 macOS Setup
1. **Install Android Studio**: [Download here](https://developer.android.com/studio).
2. **Install NDK & CMake**: 
   - Open Android Studio > Settings > Languages & Frameworks > Android SDK > SDK Tools.
   - Check **NDK (Side by side)** and **CMake**.
3. **Command Line Setup**:
   ```bash
   # Clone the repository
   git clone https://github.com/Ashish-Maurya-official/LLMApp.git
   cd LLMApp

   # Add adb to your path (Optional but recommended)
   echo 'export PATH=$PATH:~/Library/Android/sdk/platform-tools' >> ~/.zshrc
   source ~/.zshrc

   # Build the project
   ./gradlew assembleDebug
   ```

### 🪟 Windows Setup
1. **Install Android Studio**: [Download here](https://developer.android.com/studio).
2. **Install NDK & CMake**: (Same steps as macOS).
3. **Command Line Setup** (PowerShell or Command Prompt):
   ```powershell
   # Clone the repository
   git clone https://github.com/Ashish-Maurya-official/LLMApp.git
   cd LLMApp

   # Build the project
   .\gradlew.bat assembleDebug
   ```

---

## 📱 Running on Device

1. **Enable Developer Options**: 
   - On your Android device, go to *Settings > About Phone > Tap 'Build Number' 7 times*.
   - Enable *USB Debugging* in *Developer Options*.
2. **Connect Device**:
   - **Mac**: `/Users/<your-user>/Library/Android/sdk/platform-tools/adb devices`
   - **Windows**: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe devices`
3. **Install & Run**:
   ```bash
   # Use Gradle to install directly
   ./gradlew installDebug (Mac/Linux)
   .\gradlew.bat installDebug (Windows)
   ```

---

## 🧠 Model Configuration

1. **Format**: Download any model in `.gguf` format (e.g., Gemma-2b-it-Q4_K_M.gguf).
2. **Transfer**: Move the file to your phone's storage (e.g., using Android File Transfer on Mac or simple copy-paste on Windows).
3. **Select**: Open the LLMApp, tap the **Folder Icon** in the top bar, and pick your model file.

---

## 🛠 Troubleshooting
- **Build Failure**: Ensure your `local.properties` points to the correct `sdk.dir`.
- **NDK Missing**: Check `app/build.gradle.kts` matches your installed NDK version if specified.
- **Device Not Found**: Try `adb kill-server` followed by `adb start-server`.
