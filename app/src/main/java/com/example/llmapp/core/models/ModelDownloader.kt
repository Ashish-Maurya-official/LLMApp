package com.example.llmapp.core.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class ModelDownloader(private val context: Context) {

    /**
     * Enqueues a download and returns a Flow that emits progress [0.0 - 1.0].
     * Emits 1.0 exactly once when complete, then closes.
     */
    fun downloadModel(url: String, fileName: String): Flow<Float> {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading $fileName")
            .setDescription("Downloading AI Model for offline inference")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        return flow {
            emit(0f)
            while (true) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_RUNNING -> {
                            if (total > 0) emit((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.99f))
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            emit(1.0f)
                            return@flow
                        }
                        DownloadManager.STATUS_FAILED -> {
                            emit(-1f) // sentinel for failure
                            return@flow
                        }
                        else -> { /* PENDING / PAUSED */ }
                    }
                } else {
                    cursor?.close()
                }
                delay(800) // poll every 800ms
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getDownloadedModelPath(fileName: String): String? {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        return if (file.exists()) file.absolutePath else null
    }

    fun deleteModel(fileName: String): Boolean {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        return if (file.exists()) file.delete() else false
    }

    fun getLocalModels(): List<String> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".bin") || it.name.endsWith(".litertlm") }
            ?.map { it.absolutePath } ?: emptyList()
    }
}
