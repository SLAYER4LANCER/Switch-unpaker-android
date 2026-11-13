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

    fun unpack(input: File, outRoot: File, keysFile: File, opt: UnpackOptions): File {
        if (!hactool.exists()) throw Exception("hactool is missing from this build of the app.")
        outRoot.mkdirs()

        val id = UUID.randomUUID().toString().take(8)
        val tmp = File(outRoot, "_unpack_tmp_$id")
        val raw = File(tmp, "raw")
        val content = File(tmp, "content")
        val keys = File(tmp, "hactool_keys.keys")

        try {
            raw.mkdirs()
            content.mkdirs()
            writeFilteredKeys(keysFile, keys)

            val base = listOf(hactool.absolutePath, "-k", keys.absolutePath, "--disablekeywarns")

            log("Reading ${input.name} ...")
            val ext = input.extension.lowercase()
            val readCmd = if (ext == "xci") {
                base + listOf("-t", "xci", "--securedir=${raw.absolutePath}", input.absolutePath)
            } else {
                base + listOf("-t", "pfs0", "--outdir=${raw.absolutePath}", input.absolutePath)
            }
            run(readCmd)

            val ncas = raw.walkTopDown()
                .filter { it.isFile && it.extension.equals("nca", true) && !it.name.endsWith(".cnmt.nca", true) }
                .sortedByDescending { it.length() }
                .toList()
            if (ncas.isEmpty()) {
                throw Exception("No NCA files found. Keys may be too old, or the file is corrupt, compressed or unsupported.")
            }
            log("Found ${ncas.size} NCA files.")

            val titleKey = readTitleKey(raw)
            if (titleKey != null) log("Title key read from ticket.")

            val exefs = File(content, "exefs")
            val romfs = File(content, "romfs")
            val control = File(content, "control")
            val legal = File(content, "legal")
            val data = File(content, "data")

            val steps = mutableListOf<Step>()
            steps += Step("exefs+romfs", "program",
                listOf("--exefsdir=${exefs.absolutePath}", "--romfsdir=${romfs.absolutePath}"),
                listOf(exefs, romfs))
            if (opt.control || opt.naming == Naming.TITLE) {
                steps += Step("control", "control", listOf("--romfsdir=${control.absolutePath}"), listOf(control))
            }
            if (opt.legal) {
                steps += Step("legal", "manual", listOf("--romfsdir=${legal.absolutePath}"), listOf(legal))
            }
            if (opt.data) {
                steps += Step("data", "data", listOf("--romfsdir=${data.absolutePath}"), listOf(data))
            }

            val done = mutableSetOf<String>()
            for ((index, nca) in ncas.withIndex()) {
                for (s in steps) {
                    if (s.label in done) continue
                    val before = s.dirs.sumOf { countFiles(it) }
                    s.dirs.forEach { it.mkdirs() }
                    val cmd = base + listOf("-t", "nca") +
                        (if (titleKey != null) listOf("--titlekey=$titleKey") else emptyList()) +
                        listOf("--xcontenttype=${s.type}") + s.args + nca.absolutePath
                    run(cmd)
                    if (s.dirs.sumOf { countFiles(it) } > before) {
                        done += s.label
                        log("NCA ${index + 1}/${ncas.size}: extracted ${s.label}")
                        break
                    }
                }
                if (done.size == steps.size) break
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

    /** NSP ticket: the 16-byte title key sits at offset 0x180. */
    private fun readTitleKey(raw: File): String? {
        val tik = raw.walkTopDown().firstOrNull {
            it.isFile && it.extension.equals("tik", true) && it.length() >= 0x190
        } ?: return null
        RandomAccessFile(tik, "r").use { f ->
            f.seek(0x180)
            val b = ByteArray(16)
            f.readFully(b)
            return b.joinToString("") { "%02x".format(it) }
        }
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
