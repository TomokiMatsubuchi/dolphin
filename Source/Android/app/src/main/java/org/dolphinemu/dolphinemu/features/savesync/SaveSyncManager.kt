// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.savesync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SaveSyncManager {
    private const val TAG = "SaveSyncManager"

    private const val PREFS_NAME = "SaveSync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_SYNC_STATE = "sync_state"

    private const val GC_DIR = "GC"
    private const val STATE_SAVES_DIR = "StateSaves"

    private const val MTIME_TOLERANCE_MS = 2000L

    private val SYNC_DIRS = listOf(GC_DIR, STATE_SAVES_DIR)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        return try {
            prefs(context).getBoolean(KEY_ENABLED, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read enabled state", e)
            false
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return try {
            Uri.parse(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stored tree uri", e)
            null
        }
    }

    fun setTreeUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    private fun loadSyncState(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_SYNC_STATE, null)
        if (raw != null) {
            try {
                return JSONObject(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse sync state, starting fresh", e)
            }
        }
        return JSONObject()
    }

    private fun saveSyncState(context: Context, state: JSONObject) {
        prefs(context).edit().putString(KEY_SYNC_STATE, state.toString()).apply()
    }

    private fun recordSynced(context: Context, state: JSONObject, relativePath: String, size: Long, mtime: Long) {
        val entry = JSONObject()
        entry.put("size", size)
        entry.put("mtime", mtime)
        state.put(relativePath, entry)
    }

    private fun stateMatches(state: JSONObject, relativePath: String, size: Long, mtime: Long): Boolean {
        val entry = state.optJSONObject(relativePath) ?: return false
        return entry.optLong("size", -1L) == size && entry.optLong("mtime", -1L) == mtime
    }

    private fun tryGetUserDirectory(): File? {
        return try {
            File(DirectoryInitialization.getUserDirectory())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "User directory is not ready", e)
            null
        }
    }

    private fun relativePath(file: File, root: File): String {
        return file.relativeTo(root).path.replace(File.separatorChar, '/')
    }

    private fun backupTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    private fun indexRemoteFiles(directory: DocumentFile, prefix: String, out: MutableMap<String, DocumentFile>) {
        val children = directory.listFiles()
        for (child in children) {
            try {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    indexRemoteFiles(child, "$prefix/$name", out)
                } else if (child.isFile) {
                    out["$prefix/$name"] = child
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to index remote entry", e)
            }
        }
    }

    fun pullFromRemote(context: Context): String {
        val state = loadSyncState(context)

        val userDirectory = tryGetUserDirectory()
            ?: return "Import failed: Dolphin user directory is not ready"

        val treeUri = getTreeUri(context)
            ?: return "Import failed: no remote folder selected"

        val tree = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open remote tree", e)
            null
        } ?: return "Import failed: could not open remote folder"

        val remoteRoots = mutableMapOf<String, DocumentFile>()
        val missing = mutableListOf<String>()
        for (name in SYNC_DIRS) {
            val child = tree.findFile(name)
            if (child != null && child.isDirectory) {
                remoteRoots[name] = child
            } else {
                missing.add(name)
            }
        }

        if (remoteRoots.isEmpty()) {
            return "Import complete: nothing to import (missing ${missing.joinToString(", ")})"
        }

        var downloaded = 0
        var unchanged = 0
        var skipped = 0
        var backedUp = 0
        var failed = 0

        for ((directory, remoteRoot) in remoteRoots) {
            val localRoot = File(userDirectory, directory)
            val remoteFiles = mutableMapOf<String, DocumentFile>()
            try {
                indexRemoteFiles(remoteRoot, directory, remoteFiles)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to index remote subdirectory $directory", e)
                failed++
                continue
            }

            for ((relativePath, remoteDoc) in remoteFiles) {
                try {
                    val remoteSize = remoteDoc.length()
                    val remoteMtime = remoteDoc.lastModified()

                    val localFile = File(userDirectory, relativePath.replace('/', File.separatorChar))
                    val existsLocally = localFile.exists()

                    if (existsLocally && stateMatches(state, relativePath, remoteSize, remoteMtime)) {
                        unchanged++
                        continue
                    }

                    if (!existsLocally) {
                        downloadFile(context, remoteDoc, localFile)
                        recordSynced(context, state, relativePath, remoteSize, remoteMtime)
                        downloaded++
                        continue
                    }

                    val localSize = localFile.length()
                    val localMtime = localFile.lastModified()

                    val remoteNewer = remoteMtime > localMtime + MTIME_TOLERANCE_MS
                    val remoteUnknownNewer = remoteMtime == 0L && remoteSize != localSize

                    if (remoteNewer || remoteUnknownNewer) {
                        if (backupLocalFile(localFile)) {
                            backedUp++
                        }
                        downloadFile(context, remoteDoc, localFile)
                        recordSynced(context, state, relativePath, remoteSize, remoteMtime)
                        downloaded++
                    } else {
                        skipped++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import $relativePath", e)
                    failed++
                }
            }
        }

        saveSyncState(context, state)

        val summary = StringBuilder("Import complete: $downloaded downloaded, $unchanged unchanged, $skipped skipped (local newer)")
        if (missing.isNotEmpty()) {
            summary.append(", missing remote dirs: ${missing.joinToString(", ")}")
        }
        if (backedUp > 0) {
            summary.append(", $backedUp backed up")
        }
        if (failed > 0) {
            summary.append(", $failed failed")
        }
        return summary.toString()
    }

    private fun downloadFile(context: Context, remoteDoc: DocumentFile, localFile: File) {
        val parent = localFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(localFile.absolutePath + ".tmp-savesync")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        context.contentResolver.openInputStream(remoteDoc.uri).use { input ->
            if (input == null) {
                throw IllegalStateException("Could not open remote input stream")
            }
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (localFile.exists()) {
            localFile.delete()
        }
        if (!tempFile.renameTo(localFile)) {
            throw IllegalStateException("Could not move temp file into place")
        }

        localFile.setLastModified(remoteDoc.lastModified())
    }

    private fun backupLocalFile(localFile: File): Boolean {
        return try {
            val parent = localFile.parentFile ?: return false
            val backupName = "${localFile.name}.bak-${backupTimestamp()}"
            val backupFile = File(parent, backupName)
            localFile.copyTo(backupFile, overwrite = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back up ${localFile.name}", e)
            false
        }
    }

    fun pushLocalChanges(context: Context): String? {
        if (!isEnabled(context)) {
            return null
        }
        val treeUri = getTreeUri(context) ?: return null

        val userDirectory = tryGetUserDirectory()
            ?: return null

        val tree = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open remote tree", e)
            null
        } ?: return null

        val state = loadSyncState(context)

        var uploaded = 0
        var unchanged = 0

        for (directory in SYNC_DIRS) {
            val localRoot = File(userDirectory, directory)
            if (!localRoot.isDirectory) {
                continue
            }

            val remoteRoot = findOrCreateDirectory(tree, directory) ?: continue

            val localFiles = localRoot.walkTopDown().filter { it.isFile }

            for (localFile in localFiles) {
                try {
                    val relativePath = relativePath(localFile, userDirectory)
                    val localSize = localFile.length()
                    val localMtime = localFile.lastModified()

                    if (stateMatches(state, relativePath, localSize, localMtime)) {
                        unchanged++
                        continue
                    }

                    val remoteParent = findOrCreateDirectory(remoteRoot, localFile.parentFile!!.relativeTo(localRoot).path)
                        ?: throw IllegalStateException("Could not create remote directory")

                    val remoteDoc = findOrCreateFile(remoteParent, localFile.name)
                        ?: throw IllegalStateException("Could not create remote file")

                    val uploadTarget = if (remoteDoc.length() > 0 && (remoteDoc.length() != localSize || remoteDoc.lastModified() != localMtime)) {
                        tryBackupRemote(context, remoteParent, remoteDoc)
                    } else {
                        remoteDoc
                    }

                    uploadFile(context, uploadTarget, localFile)
                    recordSynced(context, state, relativePath, localSize, localMtime)
                    uploaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload ${localFile.name}", e)
                }
            }
        }

        saveSyncState(context, state)

        if (uploaded == 0) {
            return null
        }

        return "Upload complete: $uploaded uploaded, $unchanged unchanged"
    }

    private fun tryBackupRemote(context: Context, remoteParent: DocumentFile, remoteDoc: DocumentFile): DocumentFile {
        try {
            val name = remoteDoc.name ?: return remoteDoc
            val backupName = "$name.bak-${backupTimestamp()}"
            DocumentsContract.renameDocument(context.contentResolver, remoteDoc.uri, backupName)
            return findOrCreateFile(remoteParent, name) ?: remoteDoc
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back up remote file, continuing with upload", e)
            return remoteDoc
        }
    }

    private fun uploadFile(context: Context, remoteDoc: DocumentFile, localFile: File) {
        context.contentResolver.openOutputStream(remoteDoc.uri, "w").use { output ->
            if (output == null) {
                throw IllegalStateException("Could not open remote output stream")
            }
            localFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun findOrCreateDirectory(root: DocumentFile, relativePath: String): DocumentFile? {
        if (relativePath.isEmpty() || relativePath == ".") {
            return root
        }
        var current: DocumentFile = root
        val parts = relativePath.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        for (part in parts) {
            val existing = current.findFile(part)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(part) ?: return null
            }
        }
        return current
    }

    private fun findOrCreateFile(parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null && existing.isFile) {
            return existing
        }
        return parent.createFile("application/octet-stream", name)
    }
}
