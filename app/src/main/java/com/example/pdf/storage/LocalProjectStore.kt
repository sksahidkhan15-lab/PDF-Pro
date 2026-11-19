package com.example.pdf.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pdf.model.LocalProjectSummary
import com.example.pdf.model.PdfPageModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private local project storage (Android equivalent of IndexedDB) keeping an untouched copy
 * of the original PDF alongside saved edit states so work persists safely across sessions.
 */
class LocalProjectStore(private val context: Context) {

  private val projectsDir: File
    get() = File(context.filesDir, "pdf_studio_projects").apply { mkdirs() }

  suspend fun createProjectFromUri(uri: Uri): Result<Pair<String, File>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val displayName = resolveDisplayName(uri) ?: "Document.pdf"
        val projectId = UUID.randomUUID().toString().take(12)
        val dir = File(projectsDir, projectId).apply { mkdirs() }
        val originalFile = File(dir, "original.pdf")

        context.contentResolver.openInputStream(uri)?.use { input ->
          originalFile.outputStream().use { output ->
            input.copyTo(output)
          }
        } ?: throw IllegalStateException("Could not open the selected PDF stream.")

        saveProjectMetadata(
          projectId = projectId,
          displayName = displayName,
          pages = emptyList(),
        )
        projectId to originalFile
      }
    }

  suspend fun createProjectFromFile(sourceFile: File, displayName: String): Result<Pair<String, File>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val projectId = UUID.randomUUID().toString().take(12)
        val dir = File(projectsDir, projectId).apply { mkdirs() }
        val originalFile = File(dir, "original.pdf")
        sourceFile.copyTo(originalFile, overwrite = true)
        saveProjectMetadata(projectId, displayName, emptyList())
        projectId to originalFile
      }
    }

  fun getProjectOriginalFile(projectId: String): File {
    return File(File(projectsDir, projectId), "original.pdf")
  }

  suspend fun saveProjectState(
    projectId: String,
    displayName: String,
    pages: List<PdfPageModel>,
  ) = withContext(Dispatchers.IO) {
    saveProjectMetadata(projectId, displayName, pages)
  }

  suspend fun loadSavedEdits(projectId: String): SavedProjectState? =
    withContext(Dispatchers.IO) {
      val metaFile = File(File(projectsDir, projectId), "state.json")
      if (!metaFile.exists()) return@withContext null
      try {
        val json = JSONObject(metaFile.readText())
        val editsMap = HashMap<String, String>()
        val editsObj = json.optJSONObject("edits")
        if (editsObj != null) {
          val keys = editsObj.keys()
          while (keys.hasNext()) {
            val k = keys.next()
            editsMap[k] = editsObj.getString(k)
          }
        }
        val rotationsMap = HashMap<Int, Int>()
        val rotArr = json.optJSONArray("rotations")
        if (rotArr != null) {
          for (i in 0 until rotArr.length()) {
            val entry = rotArr.getJSONObject(i)
            rotationsMap[entry.getInt("pageIndex")] = entry.getInt("deg")
          }
        }
        SavedProjectState(
          projectId = projectId,
          displayName = json.optString("displayName", "Document.pdf"),
          editsBySegmentId = editsMap,
          rotationsByPage = rotationsMap,
        )
      } catch (_: Exception) {
        null
      }
    }

  suspend fun listRecentProjects(): List<LocalProjectSummary> =
    withContext(Dispatchers.IO) {
      val dirs = projectsDir.listFiles { file -> file.isDirectory } ?: return@withContext emptyList()
      dirs.mapNotNull { dir ->
        val orig = File(dir, "original.pdf")
        val meta = File(dir, "state.json")
        if (!orig.exists() || !meta.exists()) return@mapNotNull null
        try {
          val json = JSONObject(meta.readText())
          LocalProjectSummary(
            projectId = dir.name,
            displayName = json.optString("displayName", "Document.pdf"),
            lastModifiedEpochMs = json.optLong("lastModified", meta.lastModified()),
            pageCount = json.optInt("pageCount", 1),
            editCount = json.optJSONObject("edits")?.length() ?: 0,
          )
        } catch (_: Exception) {
          null
        }
      }.sortedByDescending { it.lastModifiedEpochMs }.take(8)
    }

  suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
    File(projectsDir, projectId).deleteRecursively()
  }

  private fun saveProjectMetadata(
    projectId: String,
    displayName: String,
    pages: List<PdfPageModel>,
  ) {
    val dir = File(projectsDir, projectId).apply { mkdirs() }
    val editsJson = JSONObject()
    val rotationsArr = JSONArray()
    for (page in pages) {
      if (page.userRotationDeg != 0) {
        rotationsArr.put(
          JSONObject().apply {
            put("pageIndex", page.pageIndex)
            put("deg", page.userRotationDeg)
          }
        )
      }
      for (seg in page.segments) {
        if (seg.isEdited) {
          editsJson.put(seg.id, seg.currentText)
        }
      }
    }

    val root = JSONObject().apply {
      put("projectId", projectId)
      put("displayName", displayName)
      put("lastModified", System.currentTimeMillis())
      put("pageCount", pages.size.coerceAtLeast(1))
      put("edits", editsJson)
      put("rotations", rotationsArr)
    }
    File(dir, "state.json").writeText(root.toString())
  }

  private fun resolveDisplayName(uri: Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
  }

  data class SavedProjectState(
    val projectId: String,
    val displayName: String,
    val editsBySegmentId: Map<String, String>,
    val rotationsByPage: Map<Int, Int>,
  )
}
