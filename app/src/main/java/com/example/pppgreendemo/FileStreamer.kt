package com.example.ppggreendemo

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.security.KeyException

class FileStreamer(private val outputFolder: File, private val header: String) {

    companion object {
        private const val LOG_TAG = "FileStreamer"
    }

    private val fileWriters = mutableMapOf<String, BufferedWriter>()

    fun addFile(writerId: String, fileName: String, customHeader: String? = null)  {
        if (fileWriters.containsKey(writerId)) {
            Log.w(LOG_TAG, "addFile: $writerId already exists.")
            return
        }
        try {
            val file = File(outputFolder, fileName)
            val writer = BufferedWriter(FileWriter(file))
            writer.append(customHeader ?: header)
            writer.flush()
            fileWriters[writerId] = writer
            Log.d(LOG_TAG, "$fileName created at ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Error creating file $fileName: ${e.message}")
        }
    }

    fun addRecord(writerId: String, values: String) {
        synchronized(this) {
            val writer = fileWriters[writerId]
                ?: throw KeyException("addRecord: $writerId not found.")
            try {
                writer.write(values)
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Error writing to $writerId: ${e.message}")
            }
        }
    }

    fun endFiles() {
        synchronized(this) {
            for ((id, writer) in fileWriters) {
                try {
                    writer.flush()
                    writer.close()
                    Log.d(LOG_TAG, "Closed writer for $id")
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "Error closing writer for $id: ${e.message}")
                }
            }
        }
    }
}
