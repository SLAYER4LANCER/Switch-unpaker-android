package com.switchunpacker

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

enum class Naming { FILE, TITLE, CUSTOM }

class UnpackOptions(
    val naming: Naming,
    val custom: String,
    val control: Boolean,
    val legal: Boolean,
    val data: Boolean
)

/** Runs the bundled hactool binary and organises its output. */
class Unpacker(private val hactool: File, private val log: (String) -> Unit) {

    private class Step(
        val label: String,
        val type: String,
        val args: List<String>,
        val dirs: List<File>
    )

    /** A read-out container (NSP/XCI): its NCA files and every title key found in its tickets. */
    private class Container(val ncas: List<File>, val keys: List<String?>)

    fun unpack(
        input: File,
        outRoot: File,
        keysFile: File,
        opt: UnpackOptions,
        update: File? = null,
        dlcs: List<File> = emptyList()
    ): File {
        if (!hactool.exists()) throw Exception("hactool is missing from this build of the app.")
        outRoot.mkdirs()

        val id = UUID.randomUUID().toString().take(8)
        val tmp = File(outRoot, "_unpack_tmp_$id")
        val content = File(tmp, "content")
        val keys = File(tmp, "hactool_keys.keys")

        try {
            tmp.mkdirs()
            content.mkdirs()
            writeFilteredKeys(keysFile, keys)

            val base = listOf(hactool.absolutePath, "-k", keys.absolutePath, "--disablekeywarns")

            log("Reading ${input.name} ...")
            val baseC = readContainer(base, input, File(tmp, "raw"))
            if (baseC.ncas.isEmpty()) {
                throw Exception("No NCA files found. Keys may be too old, or the file is corrupt, compressed or unsupported.")
            }
            log("Found ${baseC.ncas.size} NCA files.")

            val exefs = File(content, "exefs")
            val romfs = File(content, "romfs")
            val control = File(content, "control")
            val legal = File(content, "legal")
            val data = File(content, "data")
            val wantControl = opt.control || opt.naming == Naming.TITLE

            val steps = mutableListOf<Step>()
            steps += Step("exefs+romfs", "program",
                listOf("--exefsdir=${exefs.absolutePath}", "--romfsdir=${romfs.absolutePath}"),
                listOf(exefs, romfs))
            if (wantControl) {
                steps += Step("control", "control", listOf("--romfsdir=${control.absolutePath}"), listOf(control))
            }
            if (opt.legal) {
                steps += Step("legal", "manual", listOf("--romfsdir=${legal.absolutePath}"), listOf(legal))
            }
            if (opt.data) {
                steps += Step("data", "data", listOf("--romfsdir=${data.absolutePath}"), listOf(data))
            }

            val done = mutableSetOf<String>()
            var baseProgram: File? = null
            for ((index, nca) in baseC.ncas.withIndex()) {
                for (s in steps) {
                    if (s.label in done) continue
                    if (tryExtract(base, nca, baseC.keys, s.type, s.args, s.dirs)) {
                        done += s.label
                        if (s.label == "exefs+romfs") baseProgram = nca
                        log("NCA ${index + 1}/${baseC.ncas.size}: extracted ${s.label}")
                        break
                    }
                }
                if (done.size == steps.size) break
            }

            if (update != null) {
                applyUpdate(base, update, tmp, content, baseProgram, wantControl)
            }

            for ((i, dlc) in dlcs.withIndex()) {
                extractDlc(base, dlc, File(tmp, "raw_dlc_$i"), content)
            }

            val folderName = when (opt.naming) {
                Naming.FILE -> input.nameWithoutExtension
                Naming.CUSTOM -> opt.custom.trim().ifEmpty { input.nameWithoutExtension }
                Naming.TITLE -> readTitle(control) ?: run {
                    log("No game title found, using the file name instead.")
                    input.nameWithoutExtension
                }
            }

            if (!opt.control) control.deleteRecursively()
            for (d in listOf(exefs, romfs, control, legal, data)) {
                if (d.exists() && countFiles(d) == 0) d.deleteRecursively()
            }
            if (countFiles(content) == 0) {
                throw Exception("Nothing was extracted. Keys may be too old for this game, or the file is not supported.")
            }

            val safe = sanitize(folderName)
            var dest = File(outRoot, safe)
            var n = 2
            while (dest.exists()) {
                dest = File(outRoot, "$safe ($n)")
                n++
            }
            if (!content.renameTo(dest)) {
                content.copyRecursively(dest, overwrite = false)
            }
            return dest
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Merges an update into the extracted base game. hactool patches the romfs when it is
     * given the base program NCA (--basenca). The exefs comes straight from the update.
     */
    private fun applyUpdate(
        base: List<String>, update: File, tmp: File, content: File,
        baseProgram: File?, wantControl: Boolean
    ) {
        log("Reading update ${update.name} ...")
        val uc = readContainer(base, update, File(tmp, "raw_update"))
        if (uc.ncas.isEmpty()) {
            log("Update: no NCA files found, skipping it.")
            return
        }
        if (baseProgram == null) {
            log("Update skipped: the base game's main NCA was not found.")
            return
        }

        val ucontent = File(tmp, "content_update")
        val uExefs = File(ucontent, "exefs")
        val uRomfs = File(ucontent, "romfs")
        val uControl = File(ucontent, "control")
        var patched = false
        var gotControl = false

        for (nca in uc.ncas) {
            if (!patched) {
                val ok = tryExtract(
                    base, nca, uc.keys, "program",
                    listOf("--exefsdir=${uExefs.absolutePath}", "--romfsdir=${uRomfs.absolutePath}"),
                    listOf(uExefs, uRomfs),
                    listOf("--basenca=${baseProgram.absolutePath}")
                )
                if (ok) {
                    patched = true
                    log("Update applied to exefs and romfs.")
                    continue
                }
            }
            if (wantControl && !gotControl &&
                tryExtract(base, nca, uc.keys, "control", listOf("--romfsdir=${uControl.absolutePath}"), listOf(uControl))
            ) {
                gotControl = true
                log("Update control data found.")
            }
            if (patched && (gotControl || !wantControl)) break
        }

        if (!patched) {
            log("Could not apply the update, so the base game was kept as it is.")
            log("Tip: if the base game is an NSP, its title key can differ from the update's, which hactool cannot handle. An XCI base game works more reliably.")
            return
        }

        replaceDir(uExefs, File(content, "exefs"))
        replaceDir(uRomfs, File(content, "romfs"))
        if (gotControl) replaceDir(uControl, File(content, "control"))
        File(tmp, "raw_update").deleteRecursively()
    }

    private fun extractDlc(base: List<String>, dlc: File, rawDir: File, content: File) {
        log("Reading DLC ${dlc.name} ...")
        val dc = readContainer(base, dlc, rawDir)
        val dest = File(File(content, "dlc"), sanitize(dlc.nameWithoutExtension))
        var any = false
        for (nca in dc.ncas) {
            if (tryExtract(base, nca, dc.keys, "data", listOf("--romfsdir=${dest.absolutePath}"), listOf(dest))) {
                any = true
            }
        }
        log(if (any) "DLC extracted: ${dest.name}" else "DLC had nothing extractable: ${dlc.name}")
        rawDir.deleteRecursively()
    }

    private fun readContainer(base: List<String>, file: File, rawDir: File): Container {
        rawDir.mkdirs()
        val cmd = if (file.extension.equals("xci", true)) {
            base + listOf("-t", "xci", "--securedir=${rawDir.absolutePath}", file.absolutePath)
        } else {
            base + listOf("-t", "pfs0", "--outdir=${rawDir.absolutePath}", file.absolutePath)
        }
        run(cmd)

        val ncas = rawDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("nca", true) && !it.name.endsWith(".cnmt.nca", true) }
            .sortedByDescending { it.length() }
            .toList()
        val keys: List<String?> = readTitleKeys(rawDir) + listOf<String?>(null)
        return Container(ncas, keys)
    }

    /** Tries each title key (and finally none) until the target folders gain files. */
    private fun tryExtract(
        base: List<String>, nca: File, keys: List<String?>, type: String,
        args: List<String>, dirs: List<File>, extra: List<String> = emptyList()
    ): Boolean {
        for (k in keys) {
            val before = dirs.sumOf { countFiles(it) }
            dirs.forEach { it.mkdirs() }
            val cmd = base + listOf("-t", "nca") +
                (if (k != null) listOf("--titlekey=$k") else emptyList()) +
                extra + listOf("--xcontenttype=$type") + args + nca.absolutePath
            run(cmd)
            if (dirs.sumOf { countFiles(it) } > before) return true
        }
        return false
    }

    private fun replaceDir(from: File, to: File) {
        if (!from.exists()) return
        to.deleteRecursively()
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
    }

    private fun run(cmd: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        return p.waitFor() to text
    }

    private fun countFiles(d: File): Int =
        if (d.exists()) d.walkTopDown().count { it.isFile } else 0

    private fun writeFilteredKeys(src: File, dst: File) {
        val rx = Regex("^\\s*([A-Za-z0-9_]+)\\s*=\\s*([0-9A-Fa-f]{32}|[0-9A-Fa-f]{64})\\s*$")
        val lines = src.readLines().mapNotNull { line ->
            rx.matchEntire(line)?.let { m -> "${m.groupValues[1].lowercase()} = ${m.groupValues[2].lowercase()}" }
        }
        if (lines.none { it.startsWith("header_key") }) {
            throw Exception("header_key not found. Your prod.keys is incomplete or the wrong file.")
        }
        dst.writeText(lines.joinToString("\n") + "\n")
    }

    /** NSP tickets: the 16-byte title key sits at offset 0x180 of each .tik file. */
    private fun readTitleKeys(raw: File): List<String> {
        val out = mutableListOf<String>()
        raw.walkTopDown()
            .filter { it.isFile && it.extension.equals("tik", true) && it.length() >= 0x190 }
            .forEach { tik ->
                RandomAccessFile(tik, "r").use { f ->
                    f.seek(0x180)
                    val b = ByteArray(16)
                    f.readFully(b)
                    val hex = b.joinToString("") { "%02x".format(it) }
                    if (hex !in out) out += hex
                }
            }
        return out
    }

    /** control.nacp: 16 language slots of 0x300 bytes, the name is the first 0x200 bytes. */
    private fun readTitle(controlDir: File): String? {
        val nacp = controlDir.walkTopDown().firstOrNull {
            it.isFile && it.name.equals("control.nacp", true)
        } ?: return null
        val b = nacp.readBytes()
        for (i in 0 until 16) {
            val off = i * 0x300
            if (off + 0x200 > b.size) break
            var end = off
            while (end < off + 0x200 && b[end] != 0.toByte()) end++
            if (end > off) {
                val name = String(b, off, end - off, Charsets.UTF_8).trim()
                if (name.isNotEmpty()) return name
            }
        }
        return null
    }

    private fun sanitize(name: String): String {
        var s = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.')
        if (s.isEmpty()) s = "unpacked"
        return s
    }
}
