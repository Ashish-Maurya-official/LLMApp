package com.example.llmapp.core.skills

import android.content.Context
import android.hardware.camera2.CameraManager

class FlashlightSkill(private val context: Context) : Skill {
    override val name = "FlashlightControl"
    override val description = "Turns the device flashlight on or off. Arguments: action (String: 'on' or 'off')."

    override fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return "Error: Missing action argument."
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "Error: No camera found."
            
            when (action.lowercase()) {
                "on" -> {
                    cameraManager.setTorchMode(cameraId, true)
                    "Success: Flashlight turned ON."
                }
                "off" -> {
                    cameraManager.setTorchMode(cameraId, false)
                    "Success: Flashlight turned OFF."
                }
                else -> "Error: Invalid action. Use 'on' or 'off'."
            }
        } catch (e: Exception) {
            "Error executing flashlight control: ${e.message}"
        }
    }
}
