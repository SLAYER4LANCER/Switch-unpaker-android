package com.switchunpacker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val reqGame = 1
    private val reqKeys = 2
    private val reqOut = 3
    private val reqLegacyStorage = 4
    private val reqUpdate = 5
    private val reqDlc = 6
    private lateinit var updateText: TextView
    private lateinit var dlcText: TextView
    private var updateUri: Uri? = null
    private var dlcUris: List<Uri> = emptyList()

    private lateinit var prefs: SharedPreferences
    private lateinit var gameText: TextView
    private lateinit var keysText: TextView
    private lateinit var outText: TextView
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var custom: EditText
    private lateinit var rbFile: RadioButton
    private lateinit var rbTitle: RadioButton
    private lateinit var rbCustom: RadioButton
    private lateinit var cbControl: CheckBox
    private lateinit var cbLegal: CheckBox
    private lateinit var cbData: CheckBox
    private lateinit var unpackBtn: Button

    private var gameUri: Uri? = null
    private var outDir: File = File(Environment.getExternalStorageDirectory(), "SwitchUnpacker")
    private val keysFile: File get() = File(filesDir, "prod.keys")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        restoreSettings()
    }

    private fun label(text: String, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (bold) 16f else 14f
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Switch Unpacker"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Unpacks .nsp and .xci files you dumped from your own console, using your own prod.keys."
            textSize = 13f
        })

        root.addView(label("1. Game file (.nsp / .xci)", true))
        gameText = TextView(this).apply { text = "No file selected" }
        root.addView(gameText)
        root.addView(button("Choose game file") { pick(reqGame) })

        root.addView(label("Update (optional)", true))
        updateText = TextView(this).apply { text = "None. Pick the update .nsp to merge it into the game." }
        root.addView(updateText)
        root.addView(button("Choose update file") { pick(reqUpdate) })
        root.addView(button("Clear update") {
            updateUri = null
            updateText.text = "None. Pick the update .nsp to merge it into the game."
        })

        root.addView(label("DLC (optional)", true))
        dlcText = TextView(this).apply { text = "None. You can pick several DLC files." }
        root.addView(dlcText)
        root.addView(button("Choose DLC files") { pick(reqDlc, multiple = true) })
        root.addView(button("Clear DLC") {
            dlcUris = emptyList()
            dlcText.text = "None. You can pick several DLC files."
        })

        root.addView(label("2. prod.keys", true))
        keysText = TextView(this).apply { text = "Not loaded" }
        root.addView(keysText)
        root.addView(button("Choose prod.keys") { pick(reqKeys) })

        root.addView(label("3. Output folder", true))
        outText = TextView(this)
        root.addView(outText)
        root.addView(button("Choose output folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), reqOut)
        })

        root.addView(label("4. Output subfolder name", true))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbFile = RadioButton(this).apply { text = "Game file name"; id = View.generateViewId() }
        rbTitle = RadioButton(this).apply { text = "Game title (read from the game)"; id = View.generateViewId() }
        rbCustom = RadioButton(this).apply { text = "Custom name"; id = View.generateViewId() }
        group.addView(rbFile)
        group.addView(rbTitle)
        group.addView(rbCustom)
        root.addView(group)
        custom = EditText(this).apply { hint = "Custom name"; setSingleLine() }
        root.addView(custom)

        root.addView(label("5. Extra content (exefs and romfs are always extracted)", true))
        cbControl = CheckBox(this).apply { text = "Control (icons, nacp)" }
        cbLegal = CheckBox(this).apply { text = "Legal" }
        cbData = CheckBox(this).apply { text = "Data" }
        root.addView(cbControl)
        root.addView(cbLegal)
        root.addView(cbData)

        unpackBtn = button("Unpack") { startUnpack() }.apply {
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) }
        root.addView(unpackBtn, lp)

        logText = TextView(this).apply {
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            gravity = Gravity.START
            setPadding(0, dp(12), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(logText)

        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun restoreSettings() {
        when (prefs.getString("naming", "FILE")) {
            "TITLE" -> rbTitle.isChecked = true
            "CUSTOM" -> rbCustom.isChecked = true
            else -> rbFile.isChecked = true
        }
        custom.setText(prefs.getString("custom", ""))
        cbControl.isChecked = prefs.getBoolean("control", false)
        cbLegal.isChecked = prefs.getBoolean("legal", false)
        cbData.isChecked = prefs.getBoolean("data", false)
        prefs.getString("outPath", null)?.let { outDir = File(it) }
        outText.text = outDir.absolutePath
        if (keysFile.exists()) keysText.text = "prod.keys loaded"
    }

    private fun saveSettings() {
        val naming = when {
            rbTitle.isChecked -> "TITLE"
            rbCustom.isChecked -> "CUSTOM"
            else -> "FILE"
        }
        prefs.edit()
            .putString("naming", naming)
            .putString("custom", custom.text.toString())
            .putBoolean("control", cbControl.isChecked)
            .putBoolean("legal", cbLegal.isChecked)
            .putBoolean("data", cbData.isChecked)
            .putString("outPath", outDir.absolutePath)
            .apply()
    }

    private fun pick(code: Int, multiple: Boolean = false) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == reqDlc) {
            val list = mutableListOf<Uri>()
            data?.clipData?.let { c -> for (k in 0 until c.itemCount) list += c.getItemAt(k).uri }
            if (list.isEmpty()) data?.data?.let { list += it }
            dlcUris = list
            dlcText.text = if (list.isEmpty()) "None. You can pick several DLC files."
                else list.joinToString("\n") { PathUtil.displayName(this, it) }
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            reqUpdate -> {
                updateUri = uri
                updateText.text = PathUtil.displayName(this, uri)
            }
            reqGame -> {
                gameUri = uri
                gameText.text = PathUtil.displayName(this, uri)
            }
            reqKeys -> {
                try {
                    PathUtil.copyToCache(this, uri, "prod.keys.tmp").also {
                        it.copyTo(keysFile, overwrite = true)
                        it.delete()
                    }
                    keysText.text = "prod.keys loaded"
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not read keys: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            reqOut -> {
                val dir = PathUtil.dirFromTree(uri)
                if (dir == null) {
                    Toast.makeText(this, "Please choose a folder on phone or SD card storage.", Toast.LENGTH_LONG).show()
                } else {
                    outDir = dir
                    outText.text = dir.absolutePath
                }
            }
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Allow access to all files, then press Unpack again.", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                reqLegacyStorage
            )
        }
    }

    private fun logLine(s: String) {
        runOnUiThread {
            logText.append(s + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun startUnpack() {
        val uri = gameUri
        if (uri == null) {
            Toast.makeText(this, "Choose a game file first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!keysFile.exists()) {
            Toast.makeText(this, "Choose your prod.keys first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }

        saveSettings()
        val naming = when {
            rbTitle.isChecked -> Naming.TITLE
            rbCustom.isChecked -> Naming.CUSTOM
            else -> Naming.FILE
        }
        val options = UnpackOptions(
            naming, custom.text.toString(), cbControl.isChecked, cbLegal.isChecked, cbData.isChecked
        )
        val out = outDir
        val hactool = File(applicationInfo.nativeLibraryDir, "libhactool.so")

        unpackBtn.isEnabled = false
        logText.text = ""

        Thread {
            var wl: PowerManager.WakeLock? = null
            val copiedFiles = mutableListOf<File>()
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchunpacker:unpack")
                wl.acquire(6L * 60 * 60 * 1000)

                fun resolve(u: Uri): File {
                    PathUtil.fileFromUri(u)?.let { return it }
                    val name = PathUtil.displayName(this, u)
                    logLine("Copying $name into app storage first (needs free space) ...")
                    return PathUtil.copyToCache(this, u, name).also { copiedFiles += it }
                }
                val input = resolve(uri)
                val updateFile = updateUri?.let { resolve(it) }
                val dlcFiles = dlcUris.map { resolve(it) }
                val result = Unpacker(hactool, ::logLine).unpack(input, out, keysFile, options, updateFile, dlcFiles)
                logLine("Done: ${result.absolutePath}")
            } catch (e: Exception) {
                logLine("Error: ${e.message}")
            } finally {
                copiedFiles.forEach { it.delete() }
                wl?.let { if (it.isHeld) it.release() }
                runOnUiThread { unpackBtn.isEnabled = true }
            }
        }.start()
    }
}
