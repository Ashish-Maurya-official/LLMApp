package com.example.llmapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

object FileUtils {
    private const val TAG = "FileUtils"

    fun getPath(context: Context, uri: Uri): String? {
        Log.d(TAG, "Resolving URI: $uri")
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                if (isExternalStorageDocument(uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":").toTypedArray()
                    val type = split[0]
                    if ("primary".equals(type, ignoreCase = true)) {
                        return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                    }
                } else if (isDownloadsDocument(uri)) {
                    val id = DocumentsContract.getDocumentId(uri)
                    if (id.startsWith("raw:")) {
                        return id.substring(4)
                    }
                    if (id.startsWith("msf:")) {
                        // msf: is MediaStore file, try to resolve via display name in Downloads
                        return resolveByDisplayName(context, uri) ?: getDataColumn(context, uri, null, null)
                    }
                    try {
                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id)
                        )
                        return getDataColumn(context, contentUri, null, null) ?: resolveByDisplayName(context, uri)
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Failed to parse download ID as Long: $id, attempting fallback query")
                        return getDataColumn(context, uri, null, null) ?: resolveByDisplayName(context, uri)
                    }
                } else if (isMediaDocument(uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":").toTypedArray()
                    val type = split[0]
                    var contentUri: Uri? = null
                    if ("image" == type) {
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else if ("video" == type) {
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else if ("audio" == type) {
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(split[1])
                    return getDataColumn(context, contentUri, selection, selectionArgs)
                }
            } else if ("content".equals(uri.scheme, ignoreCase = true)) {
                return getDataColumn(context, uri, null, null) ?: resolveByDisplayName(context, uri)
            } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                return uri.path
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving path for URI $uri", e)
        }
        return resolveByDisplayName(context, uri)
    }

    private fun resolveByDisplayName(context: Context, uri: Uri): String? {
        Log.d(TAG, "Attempting fallback resolution by Display Name for URI: $uri")
        var displayName: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    displayName = cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get display name", e)
        }

        if (displayName != null) {
            val pathsToCheck = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
                Environment.getExternalStorageDirectory().absolutePath
            )
            for (basePath in pathsToCheck) {
                val file = java.io.File(basePath, displayName!!)
                if (file.exists() && file.canRead()) {
                    Log.d(TAG, "Found file via display name matching at: ${file.absolutePath}")
                    return file.absolutePath
                }
            }
            Log.w(TAG, "File with name $displayName not found in standard directories.")
        }
        return null
    }

    private fun getDataColumn(context: Context, uri: Uri?, selection: String?, selectionArgs: Array<String>?): String? {
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri ?: return null, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(column)
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query data column for URI $uri", e)
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}
