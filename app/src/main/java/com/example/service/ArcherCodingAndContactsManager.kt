package com.example.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DeviceContactEntry(
    val displayName: String,
    val phoneNumber: String
)

data class ContactOrScreenResolution(
    val queryName: String,
    val matchedContact: DeviceContactEntry?,
    val foundOnVisibleScreen: Boolean,
    val hasContactsPermission: Boolean,
    val phoneticCandidates: List<String>
)

data class SavedArcherArtifact(
    val id: String,
    val projectTitle: String,
    val primaryFileName: String,
    val language: String,
    val codeContent: String,
    val folderPath: String,
    val singleFilePath: String,
    val zipFilePath: String,
    val createdAtLabel: String
)

data class CodingWorkspaceState(
    val activeModelId: String = "models/gemini-4-argon",
    val isAutoModelRouting: Boolean = true,
    val currentProjectTitle: String = "Archer Smart Web & App Starter",
    val currentFileName: String = "index.html",
    val currentLanguage: String = "HTML/JS",
    val currentCode: String = DEFAULT_STARTER_CODE,
    val archerMainFolderPath: String = "/storage/emulated/0/Documents/archer",
    val lastSaveSummary: String = "কোডিং শেষে আপনার ফাইল ম্যানেজারের মেইন 'archer' ফোল্ডারে File, Folder এবং ZIP আকারে অটো-সেভ হবে।",
    val savedArtifacts: List<SavedArcherArtifact> = emptyList()
)

private const val DEFAULT_STARTER_CODE = """<!DOCTYPE html>
<html lang="bn">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>ARCHER AI — Argon 4 Generated App</title>
  <style>
    body { background: #03110B; color: #00FF66; font-family: sans-serif; text-align: center; padding: 32px; }
    .card { border: 1.5px solid #00FF66; border-radius: 16px; padding: 24px; max-width: 460px; margin: 0 auto; background: #072117; }
    button { background: #00FF66; color: #000; border: none; padding: 12px 24px; border-radius: 8px; font-weight: bold; cursor: pointer; }
  </style>
</head>
<body>
  <div class="card">
    <h1>ARCHER AI • ARGON 4</h1>
    <p>আপনার কোডিং প্রজেক্ট 'archer' ফোল্ডারে ফাইল, ফোল্ডার ও ZIP আকারে সংরক্ষিত হয়েছে।</p>
    <button onclick="alert('Archer AI Ready!')">Run Action</button>
  </div>
</body>
</html>"""

class ArcherCodingAndContactsManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val _workspaceState = MutableStateFlow(CodingWorkspaceState())
    val workspaceState: StateFlow<CodingWorkspaceState> = _workspaceState.asStateFlow()

    init {
        ensureArcherDirectoriesAndLoadExisting()
    }

    fun setActiveModel(modelId: String, autoRoute: Boolean = _workspaceState.value.isAutoModelRouting) {
        _workspaceState.value = _workspaceState.value.copy(
            activeModelId = modelId,
            isAutoModelRouting = autoRoute
        )
    }

    fun toggleAutoModelRouting(): Boolean {
        val next = !_workspaceState.value.isAutoModelRouting
        _workspaceState.value = _workspaceState.value.copy(isAutoModelRouting = next)
        return next
    }

    /**
     * Automatically selects the best model (`models/gemini-4-argon` for coding, complex reasoning,
     * and multi-step system automation; `gemini-3.8-flash` for conversational & device tasks).
     */
    fun selectBestModelForPrompt(prompt: String): String {
        val state = _workspaceState.value
        if (!state.isAutoModelRouting) return state.activeModelId

        val lower = prompt.lowercase()
        val needsArgon4 = lower.contains("argon") ||
            lower.contains("আরগন") ||
            lower.contains("কোড") ||
            lower.contains("কোডিং") ||
            lower.contains("code") ||
            lower.contains("coding") ||
            lower.contains("html") ||
            lower.contains("python") ||
            lower.contains("javascript") ||
            lower.contains("kotlin") ||
            lower.contains("script") ||
            lower.contains("ওয়েবসাইট বান") ||
            lower.contains("অ্যাপ বান") ||
            lower.contains("প্রোগ্রাম") ||
            lower.contains("zip") ||
            lower.contains("archer")

        val chosen = if (needsArgon4) "models/gemini-4-argon" else state.activeModelId
        if (chosen != state.activeModelId) {
            _workspaceState.value = state.copy(activeModelId = chosen)
        }
        return chosen
    }

    /**
     * Saves generated code into the `archer` folder in all 3 formats requested by the user:
     * 1. Single File (`archer/<fileName>`)
     * 2. Project Folder (`archer/<projectSlug>/<fileName>` + `README.md`)
     * 3. ZIP Archive (`archer/<projectSlug>.zip`)
     * Also writes to Android Public Documents/Download `archer/` via MediaStore so it is immediately
     * visible in the user's phone File Manager under `archer/`.
     */
    fun saveGeneratedCodeToArcherFolder(
        projectTitle: String,
        preferredFileName: String,
        language: String,
        rawCodeOrResponse: String
    ): SavedArcherArtifact {
        val extractedCode = extractCleanCodeBlock(rawCodeOrResponse)
        val cleanLang = language.ifBlank { detectLanguageFromCode(extractedCode, preferredFileName) }
        val ext = extensionForLanguage(cleanLang, preferredFileName)
        val slug = projectTitle
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "archer_project_${System.currentTimeMillis() % 10000}" }

        val finalFileName = if (preferredFileName.contains(".")) {
            preferredFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        } else {
            "${slug}.$ext"
        }

        val baseArcherDir = resolveWritableArcherRootDirectory()
        if (!baseArcherDir.exists()) baseArcherDir.mkdirs()

        // 1. Save as Single File directly inside archer/
        val singleFile = File(baseArcherDir, finalFileName)
        runCatching {
            singleFile.writeText(extractedCode, Charsets.UTF_8)
        }

        // 2. Save as Project Folder inside archer/<slug>/
        val projectFolder = File(baseArcherDir, slug)
        if (!projectFolder.exists()) projectFolder.mkdirs()
        val projectMainFile = File(projectFolder, finalFileName)
        val readmeFile = File(projectFolder, "README.md")
        val readmeText = buildString {
            appendLine("# $projectTitle")
            appendLine("- Generated by: ARCHER AI (Model: ${_workspaceState.value.activeModelId})")
            appendLine("- Primary File: `$finalFileName`")
            appendLine("- Language: $cleanLang")
            appendLine("- Saved in: `archer/$slug/` and `archer/$slug.zip`")
        }
        runCatching {
            projectMainFile.writeText(extractedCode, Charsets.UTF_8)
            readmeFile.writeText(readmeText, Charsets.UTF_8)
        }

        // 3. Save as .zip archive inside archer/<slug>.zip
        val zipFile = File(baseArcherDir, "$slug.zip")
        runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                zos.putNextEntry(ZipEntry("$slug/$finalFileName"))
                zos.write(extractedCode.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("$slug/README.md"))
                zos.write(readmeText.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }

        // 4. Mirror Single File, Folder File, and ZIP into Public Storage `Documents/archer` & `Download/archer`
        val publicPathSummary = mirrorToPublicArcherFolder(
            slug = slug,
            fileName = finalFileName,
            codeBytes = extractedCode.toByteArray(Charsets.UTF_8),
            zipFile = zipFile
        )

        val timeLabel = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date())
        val artifact = SavedArcherArtifact(
            id = "art_${System.currentTimeMillis()}",
            projectTitle = projectTitle.ifBlank { slug },
            primaryFileName = finalFileName,
            language = cleanLang,
            codeContent = extractedCode,
            folderPath = "$publicPathSummary/$slug/",
            singleFilePath = "$publicPathSummary/$finalFileName",
            zipFilePath = "$publicPathSummary/$slug.zip",
            createdAtLabel = timeLabel
        )

        val updatedList = listOf(artifact) + _workspaceState.value.savedArtifacts.filterNot {
            it.primaryFileName == artifact.primaryFileName && it.projectTitle == artifact.projectTitle
        }

        _workspaceState.value = _workspaceState.value.copy(
            currentProjectTitle = artifact.projectTitle,
            currentFileName = finalFileName,
            currentLanguage = cleanLang,
            currentCode = extractedCode,
            archerMainFolderPath = publicPathSummary,
            lastSaveSummary = "মেইন 'archer' ফোল্ডারে সংরক্ষিত: ১) $finalFileName ২) archer/$slug/ ফোল্ডার ৩) archer/$slug.zip",
            savedArtifacts = updatedList.take(15)
        )

        return artifact
    }

    private fun ensureArcherDirectoriesAndLoadExisting() {
        runCatching {
            val baseDir = resolveWritableArcherRootDirectory()
            if (!baseDir.exists()) baseDir.mkdirs()
            val starterFile = File(baseDir, "archer_welcome.html")
            if (!starterFile.exists()) {
                saveGeneratedCodeToArcherFolder(
                    projectTitle = "Archer AI Starter App",
                    preferredFileName = "index.html",
                    language = "HTML/JS",
                    rawCodeOrResponse = DEFAULT_STARTER_CODE
                )
            }
        }
    }

    private fun resolveWritableArcherRootDirectory(): File {
        // Try public Documents/archer first if direct file write is allowed
        try {
            val pubDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val archerPub = File(pubDocs, "archer")
            if (archerPub.exists() || archerPub.mkdirs()) {
                if (archerPub.canWrite()) return archerPub
            }
        } catch (_: Exception) {
        }

        // Next try app's external files directory /archer
        val extDir = appContext.getExternalFilesDir(null)
        if (extDir != null) {
            val archerExt = File(extDir, "archer")
            if (archerExt.exists() || archerExt.mkdirs()) return archerExt
        }

        // Fallback to internal filesDir/archer
        val internalArcher = File(appContext.filesDir, "archer")
        if (!internalArcher.exists()) internalArcher.mkdirs()
        return internalArcher
    }

    private fun mirrorToPublicArcherFolder(
        slug: String,
        fileName: String,
        codeBytes: ByteArray,
        zipFile: File
    ): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appContext.contentResolver
                // Save Single Code File in Documents/archer/
                val fileValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/archer")
                }
                resolver.insert(MediaStore.Files.getContentUri("external"), fileValues)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { it.write(codeBytes) }
                }

                // Save Project Folder file in Documents/archer/<slug>/
                val folderFileValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/archer/$slug")
                }
                resolver.insert(MediaStore.Files.getContentUri("external"), folderFileValues)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { it.write(codeBytes) }
                }

                // Save ZIP Archive in Documents/archer/<slug>.zip and Download/archer/<slug>.zip
                if (zipFile.exists()) {
                    val zipBytes = zipFile.readBytes()
                    val zipValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$slug.zip")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/archer")
                    }
                    resolver.insert(MediaStore.Files.getContentUri("external"), zipValues)?.let { uri ->
                        resolver.openOutputStream(uri)?.use { it.write(zipBytes) }
                    }

                    val dlZipValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$slug.zip")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/archer")
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlZipValues)?.let { uri ->
                        resolver.openOutputStream(uri)?.use { it.write(zipBytes) }
                    }
                }
                return "/storage/emulated/0/Documents/archer"
            }
        } catch (_: Exception) {
        }
        return resolveWritableArcherRootDirectory().absolutePath
    }

    fun shareCodeOrProject(artifact: SavedArcherArtifact) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${artifact.projectTitle} (${artifact.primaryFileName})")
            putExtra(
                Intent.EXTRA_TEXT,
                "// Saved in: ${artifact.zipFilePath}\n// File: ${artifact.primaryFileName}\n\n${artifact.codeContent}"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            appContext.startActivity(
                Intent.createChooser(shareIntent, "কোড বা প্রজেক্ট শেয়ার করুন").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private fun extractCleanCodeBlock(raw: String): String {
        val fenced = Regex("```(?:[a-zA-Z0-9_+-]+)?\\s*([\\s\\S]*?)```").find(raw)
        if (fenced != null) {
            val body = fenced.groupValues[1].trim()
            if (body.isNotBlank()) return body
        }
        return raw
            .replace(Regex("\\[\\[ACTION:.*?]]", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
    }

    private fun detectLanguageFromCode(code: String, fileName: String): String {
        val lowerName = fileName.lowercase()
        val lowerCode = code.lowercase()
        return when {
            lowerName.endsWith(".py") || lowerCode.contains("def ") || lowerCode.contains("import ") && lowerCode.contains("print(") -> "Python"
            lowerName.endsWith(".kt") || lowerCode.contains("fun main") || lowerCode.contains("@composable") -> "Kotlin"
            lowerName.endsWith(".html") || lowerCode.contains("<!doctype html") || lowerCode.contains("<html") -> "HTML/JS"
            lowerName.endsWith(".js") || lowerCode.contains("function ") || lowerCode.contains("const ") -> "JavaScript"
            lowerName.endsWith(".java") || lowerCode.contains("public class ") -> "Java"
            lowerName.endsWith(".cpp") || lowerCode.contains("#include") -> "C++"
            else -> "Code"
        }
    }

    private fun extensionForLanguage(lang: String, fileName: String): String {
        if (fileName.contains(".")) {
            return fileName.substringAfterLast('.').lowercase().ifBlank { "txt" }
        }
        return when (lang.uppercase()) {
            "PYTHON", "PY" -> "py"
            "KOTLIN", "KT" -> "kt"
            "HTML", "HTML/JS", "WEB" -> "html"
            "JAVASCRIPT", "JS" -> "js"
            "JAVA" -> "java"
            "C++", "CPP" -> "cpp"
            "JSON" -> "json"
            else -> "txt"
        }
    }

    // =========================================================================
    // Contacts List + Screen-Wide Name Scanner
    // =========================================================================

    fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Searches both the phone's Contacts (`ContactsContract`) AND the current visible screen
     * for any spoken/typed name in Bengali, Banglish, English, or Hindi.
     */
    fun findContactOrScreenTarget(
        rawSpokenNameOrCommand: String,
        savedPlatformContacts: List<Pair<String, String>> = emptyList()
    ): ContactOrScreenResolution {
        val cleanName = extractCleanPersonName(rawSpokenNameOrCommand)
        val candidates = buildPhoneticNameCandidates(cleanName)
        val hasPerm = hasReadContactsPermission()

        var matchedContact: DeviceContactEntry? = null

        // 1. Search real Android Device Contacts if permission is granted
        if (hasPerm && cleanName.isNotBlank()) {
            val allContacts = queryDeviceContacts()
            matchedContact = allContacts.firstOrNull { entry ->
                val contactLower = entry.displayName.lowercase()
                candidates.any { cand ->
                    cand.isNotBlank() && (contactLower.contains(cand.lowercase()) || cand.lowercase().contains(contactLower))
                }
            }
        }

        // 2. Also check saved PlatformAccounts / Memory Contacts if not found in phonebook
        if (matchedContact == null && cleanName.isNotBlank()) {
            val savedMatch = savedPlatformContacts.firstOrNull { (name, _) ->
                val nLower = name.lowercase()
                candidates.any { cand ->
                    cand.isNotBlank() && (nLower.contains(cand.lowercase()) || cand.lowercase().contains(nLower))
                }
            }
            if (savedMatch != null) {
                matchedContact = DeviceContactEntry(savedMatch.first, savedMatch.second)
            }
        }

        // 3. Check if the name is currently visible on the phone screen via Accessibility
        val screenTextLower = AccessibilityAutomationBus.state.value.visibleScreenText.lowercase()
        val foundOnScreen = candidates.any { cand ->
            cand.length >= 2 && screenTextLower.contains(cand.lowercase())
        }

        return ContactOrScreenResolution(
            queryName = cleanName.ifBlank { rawSpokenNameOrCommand.trim() },
            matchedContact = matchedContact,
            foundOnVisibleScreen = foundOnScreen,
            hasContactsPermission = hasPerm,
            phoneticCandidates = candidates
        )
    }

    fun queryDeviceContacts(limit: Int = 250): List<DeviceContactEntry> {
        if (!hasReadContactsPermission()) return emptyList()
        val list = mutableListOf<DeviceContactEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && list.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val number = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        list.add(DeviceContactEntry(name, number))
                    }
                }
            }
        }
        return list
    }

    private fun extractCleanPersonName(rawInput: String): String {
        return rawInput
            .replace(
                Regex(
                    "(?i)(কন্টাক্ট|কন্টেক্ট|ফোনবুক|নাম্বার|নম্বর|স্ক্রিনে|সক্রিনে|সারা স্ক্রিনে|খুঁজে|বিচারাইয়া|বিচারিয়ে|" +
                        "কল দাও|কল করো|কল কর|ফোন দাও|ফোন করো|ডায়াল করো|মেসেজ দাও|মেসেজ করো|হোয়াটসঅ্যাপে|ইমোতে|মেসেঞ্জারে|" +
                        "নামের ওপর|নামে|চাপ দাও|ক্লিক করো|ট্যাপ করো|কে|রে|এর|ভাইকে|আপুকে|স্যার|" +
                        "call|dial|contact|phone|message|whatsapp|tap|click|on screen|search|find|please|sir)"
                ),
                " "
            )
            .replace(Regex("[|,:;!?\"'()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun buildPhoneticNameCandidates(cleanName: String): List<String> {
        if (cleanName.isBlank()) return emptyList()
        val set = linkedSetOf(cleanName)
        val lower = cleanName.lowercase()

        // Common Bengali <-> English contact name mappings
        val dictionary = mapOf(
            "রহিম" to listOf("Rahim", "Rohim", "রাহিম"),
            "রাহিম" to listOf("Rahim", "Rohim", "রহিম"),
            "করিম" to listOf("Karim", "Korim"),
            "তানভীর" to listOf("Tanvir", "Tanveer"),
            "সাকিব" to listOf("Sakib", "Shakib", "শাকিব"),
            "শাকিব" to listOf("Shakib", "Sakib", "সাকিব"),
            "হাসান" to listOf("Hasan", "Hassan"),
            "হোসেন" to listOf("Hossain", "Husain"),
            "মামুন" to listOf("Mamun"),
            "সুমন" to listOf("Sumon", "Suman"),
            "রিয়ান" to listOf("Riyan", "Ryan", "Rian"),
            "রিয়ান" to listOf("Riyan", "Ryan", "Rian"),
            "আরিফ" to listOf("Arif"),
            "জাহিদ" to listOf("Jahid", "Zahid"),
            "নাহিদ" to listOf("Nahid"),
            "সজীব" to listOf("Sajib", "Sojib"),
            "ইমরান" to listOf("Imran"),
            "রাকিব" to listOf("Rakib"),
            "নাবিল" to listOf("Nabil"),
            "ফাহিম" to listOf("Fahim"),
            "মা" to listOf("Maa", "Mom", "Amma", "Ammu", "আম্মু", "আম্মা"),
            "আম্মু" to listOf("Ammu", "Mom", "Maa", "মা"),
            "বাবা" to listOf("Baba", "Abba", "Abbu", "আব্বু", "আব্বা", "Dad"),
            "আব্বু" to listOf("Abbu", "Baba", "Dad", "বাবা")
        )

        for ((key, equivalents) in dictionary) {
            if (lower.contains(key.lowercase()) || equivalents.any { lower.contains(it.lowercase()) }) {
                set.add(key)
                set.addAll(equivalents)
            }
        }

        // Also generate a simple Romanized transliteration if input is in Bengali script
        if (cleanName.any { it.code in 0x0980..0x09FF }) {
            val roman = transliterateBengaliNameToEnglish(cleanName)
            if (roman.length >= 2) {
                set.add(roman)
                set.add(roman.replaceFirstChar { it.uppercase() })
            }
        }

        return set.filter { it.isNotBlank() }
    }

    private fun transliterateBengaliNameToEnglish(bn: String): String {
        val sb = StringBuilder()
        for (ch in bn) {
            val m = when (ch) {
                'অ' -> "o"
                'আ', 'া' -> "a"
                'ই', 'ঈ', 'ি', 'ী' -> "i"
                'উ', 'ঊ', 'ু', 'ূ' -> "u"
                'এ', 'ে' -> "e"
                'ও', 'ো' -> "o"
                'ক' -> "k"
                'খ' -> "kh"
                'গ' -> "g"
                'ঘ' -> "gh"
                'চ' -> "ch"
                'ছ' -> "chh"
                'জ', 'য' -> "j"
                'ট', 'ত' -> "t"
                'ঠ', 'থ' -> "th"
                'ড', 'দ' -> "d"
                'ঢ', 'ধ' -> "dh"
                'ণ', 'ন' -> "n"
                'প' -> "p"
                'ফ' -> "f"
                'ব' -> "b"
                'ভ' -> "bh"
                'ম' -> "m"
                'র', 'ড়' -> "r"
                'ল' -> "l"
                'শ', 'ষ', 'স' -> "s"
                'হ' -> "h"
                'য়' -> "y"
                'ং' -> "ng"
                '্', 'ঁ' -> ""
                else -> if (ch.isLetterOrDigit() || ch == ' ') ch.toString() else ""
            }
            sb.append(m)
        }
        return sb.toString().trim()
    }
}
