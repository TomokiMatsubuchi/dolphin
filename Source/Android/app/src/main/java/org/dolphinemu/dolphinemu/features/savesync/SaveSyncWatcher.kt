// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.savesync

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import java.io.File

class SaveSyncWatcher(context: Context) {
    private val applicationContext: Context = context.applicationContext

    private val lock = Any()

    private val observerMask = FileObserver.CLOSE_WRITE or
        FileObserver.CREATE or
        FileObserver.MOVED_TO

    private val observers = mutableListOf<FileObserver>()

    private var watching = false

    private var debounceJob: Job? = null
    private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPush: Runnable? = null

    private val debounceDelayMs = 30_000L

    fun start() {
        synchronized(lock) {
            if (watching) {
                return
            }
            if (!SaveSyncManager.isEnabled(applicationContext) || SaveSyncManager.getTreeUri(applicationContext) == null) {
                return
            }

            val userDirectory = try {
                File(DirectoryInitialization.getUserDirectory())
            } catch (e: IllegalStateException) {
                Log.w(TAG, "User directory is not ready, not watching for save changes", e)
                return
            }

            val roots = listOf(File(userDirectory, GC_DIR), File(userDirectory, STATE_SAVES_DIR))
                .filter { it.isDirectory }

            if (roots.isEmpty()) {
                return
            }

            try {
                for (root in roots) {
                    watchRecursively(root)
                }
                watching = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start watching save directories", e)
                stopLocked()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        try {
            for (observer in observers) {
                try {
                    observer.stopWatching()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop observer", e)
                }
            }
            observers.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop observers", e)
        }

        pendingPush?.let { handler.removeCallbacks(it) }
        pendingPush = null

        debounceJob?.cancel()
        debounceJob = null

        watching = false
    }

    private fun watchRecursively(directory: File) {
        val observer = object : FileObserver(directory, observerMask) {
            override fun onEvent(event: Int, path: String?) {
                this@SaveSyncWatcher.onEvent(directory, event, path)
            }
        }
        try {
            observer.startWatching()
            observers.add(observer)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to watch ${directory.absolutePath}", e)
            return
        }

        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                watchRecursively(child)
            }
        }
    }

    private fun onEvent(directory: File, event: Int, path: String?) {
        try {
            if (path == null) return
            if ((event and FileObserver.CLOSE_WRITE) != 0) {
                schedulePush()
                return
            }
            if ((event and (FileObserver.CREATE or FileObserver.MOVED_TO)) != 0) {
                val child = File(directory, path)
                if (child.isDirectory) {
                    synchronized(lock) {
                        if (watching) {
                            watchRecursively(child)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle file event", e)
        }
    }

    private fun schedulePush() {
        pendingPush?.let { handler.removeCallbacks(it) }

        val runnable = Runnable { runPush() }
        pendingPush = runnable
        handler.postDelayed(runnable, debounceDelayMs)
    }

    private fun runPush() {
        pendingPush = null
        debounceJob?.cancel()
        debounceJob = debounceScope.launch {
            try {
                val summary = SaveSyncManager.pushLocalChanges(applicationContext)
                if (summary != null) {
                    Log.i(TAG, summary)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push local save changes", e)
            }
        }
    }

    companion object {
        private const val TAG = "SaveSyncWatcher"

        private const val GC_DIR = "GC"
        private const val STATE_SAVES_DIR = "StateSaves"
    }
}
