package dev.sadking.folio.core.dictionary

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.text.Normalizer

enum class PartOfSpeech(internal val suffix: String) {
    NOUN("noun"),
    VERB("verb"),
    ADJECTIVE("adj"),
    ADVERB("adv"),
}

data class Sense(val definition: String, val examples: List<String>, val synonyms: List<String>)

data class DictionaryEntry(
    val headword: String,
    val partOfSpeech: PartOfSpeech,
    val senses: List<Sense>,
)

/**
 * The unpacked WNDB files of Open English WordNet in [dir]: `index.<pos>` and `data.<pos>` for the
 * four parts of speech, and the `<pos>.exc` exception lists. Index files are sorted by bytes, so a
 * lookup is a binary search over the file; data lines are read at the byte offsets the index gives.
 * Nothing is loaded up front except the small exception lists. Thread-safe for reads: every read is
 * positional. Close when done.
 */
class WordNetDictionary(private val dir: File) : Closeable {
    private val channels = HashMap<String, FileChannel>()
    private val exceptionLists = HashMap<PartOfSpeech, Map<String, List<String>>>()
    @Volatile private var closed = false

    /**
     * Entries for a selection: normalised, then lemmatised per part of speech. Empty when nothing
     * matches or the selection is not a word or a short phrase.
     */
    fun define(selection: String): List<DictionaryEntry> {
        val word = WordNetText.normalise(selection) ?: return emptyList()
        val found = lookUp(word)
        if (found.isNotEmpty()) return found
        // "Café" when the dictionary has "cafe": try once more without accents.
        val plain = stripAccents(word)
        return if (plain != word) lookUp(plain) else emptyList()
    }

    private fun lookUp(word: String): List<DictionaryEntry> =
        PartOfSpeech.entries.flatMap { pos ->
            Morphy.lemmas(word, pos, ::exceptions, { p, lemma -> indexLine(p, lemma) != null })
                .mapNotNull { lemma -> entry(pos, lemma) }
        }

    private fun entry(pos: PartOfSpeech, lemma: String): DictionaryEntry? {
        val line = indexLine(pos, lemma) ?: return null
        val offsets = indexOffsets(line)
        val synsets = offsets.mapNotNull { offset -> readLine("data.${pos.suffix}", offset) }
        if (synsets.isEmpty()) return null
        val parsed = synsets.mapNotNull(::parseSynset)
        if (parsed.isEmpty()) return null
        // The data files keep the word's case ("London"); the index has it in lower case.
        val headword =
            parsed
                .asSequence()
                .flatMap { it.words.asSequence() }
                .firstOrNull { it.lowercase() == lemma.replace('_', ' ') }
                ?: lemma.replace('_', ' ')
        val senses = parsed.map { synset ->
            Sense(
                synset.definition,
                synset.examples,
                synset.words.filter { it.lowercase() != headword.lowercase() }.distinct(),
            )
        }
        return DictionaryEntry(headword, pos, senses)
    }

    /** The base forms an exception list gives for [word], or none. */
    internal fun exceptions(pos: PartOfSpeech, word: String): List<String> {
        val list =
            synchronized(exceptionLists) {
                exceptionLists.getOrPut(pos) { loadExceptions(File(dir, "${pos.suffix}.exc")) }
            }
        return list[word].orEmpty()
    }

    /** The index line for [lemma], or null when the lemma is not in the index. */
    internal fun indexLine(pos: PartOfSpeech, lemma: String): String? {
        if (lemma.isEmpty() || lemma.any { it == ' ' || it == '\n' }) return null
        val channel = channel("index.${pos.suffix}") ?: return null
        val key = "$lemma ".toByteArray(Charsets.UTF_8)
        var low = 0L
        var high = channel.size()
        while (low < high) {
            val middle = (low + high) / 2
            val start = if (middle == 0L) 0L else lineStartFrom(channel, middle) ?: high
            if (start >= high) {
                high = middle
                continue
            }
            val bytes = lineBytes(channel, start)
            val order = compareKey(bytes, key)
            when {
                order == 0 -> return String(bytes, Charsets.UTF_8)
                order < 0 -> low = start + bytes.size + 1
                else -> high = start
            }
        }
        return null
    }

    private fun readLine(file: String, offset: Long): String? {
        val channel = channel(file) ?: return null
        if (offset < 0 || offset >= channel.size()) return null
        return String(lineBytes(channel, offset), Charsets.UTF_8)
    }

    private fun channel(name: String): FileChannel? {
        if (closed) throw IOException("Dictionary closed")
        return synchronized(channels) {
            channels[name]
                ?: File(dir, name)
                    .takeIf { it.isFile }
                    ?.let { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    ?.also { channels[name] = it }
        }
    }

    override fun close() {
        closed = true
        synchronized(channels) {
            channels.values.forEach { runCatching { it.close() } }
            channels.clear()
        }
    }

    internal companion object {
        private const val CHUNK = 4096
        /** Longer than any line in the files (35,220 bytes); a longer one is not a line we read. */
        private const val MAX_LINE = 256 * 1024

        /** The start of the first line at or after [from]: the byte after the next newline. */
        fun lineStartFrom(channel: FileChannel, from: Long): Long? {
            // A line starts at [from] when the byte before it is a newline.
            var position = from - 1
            val buffer = ByteBuffer.allocate(CHUNK)
            val size = channel.size()
            while (position < size) {
                buffer.clear()
                val read = channel.read(buffer, position)
                if (read <= 0) return null
                for (i in 0 until read) {
                    if (buffer.get(i) == '\n'.code.toByte()) return position + i + 1
                }
                position += read
            }
            return null
        }

        /** The bytes of the line starting at [start], without its newline. */
        fun lineBytes(channel: FileChannel, start: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(CHUNK)
            var position = start
            while (out.size() < MAX_LINE) {
                buffer.clear()
                val read = channel.read(buffer, position)
                if (read <= 0) break
                for (i in 0 until read) {
                    val byte = buffer.get(i)
                    if (byte == '\n'.code.toByte()) {
                        out.write(buffer.array(), 0, i)
                        return out.toByteArray()
                    }
                }
                out.write(buffer.array(), 0, read)
                position += read
            }
            return out.toByteArray()
        }

        /**
         * Compares an index line with `lemma + " "` byte by byte, unsigned, as the files are sorted
         * (C locale). Licence lines start with two spaces and so sort before every key.
         */
        fun compareKey(line: ByteArray, key: ByteArray): Int {
            for (i in key.indices) {
                if (i >= line.size) return -1
                val a = line[i].toInt() and 0xFF
                val b = key[i].toInt() and 0xFF
                if (a != b) return a - b
            }
            return 0
        }

        /** The synset offsets at the end of an index line, most frequent sense first. */
        fun indexOffsets(line: String): List<Long> {
            val parts = line.trim().split(' ')
            // lemma, pos, synset_cnt, p_cnt, the pointer symbols, sense_cnt, tagsense_cnt, offsets
            val count = parts.getOrNull(2)?.toIntOrNull() ?: return emptyList()
            if (count <= 0 || parts.size < count) return emptyList()
            return parts.takeLast(count).mapNotNull { it.toLongOrNull() }
        }

        fun loadExceptions(file: File): Map<String, List<String>> {
            if (!file.isFile) return emptyMap()
            val map = HashMap<String, List<String>>()
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.startsWith("  ")) return@forEachLine
                val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                if (parts.size >= 2) map[parts[0]] = map[parts[0]].orEmpty() + parts.drop(1)
            }
            return map
        }

        /** One synset of a data line: its words, the definition and the examples. */
        class Synset(val words: List<String>, val definition: String, val examples: List<String>)

        /**
         * A data line: offset, lex_filenum, ss_type, w_cnt, then w_cnt pairs of word and lex_id,
         * then pointers and verb frames, then " | " and the gloss. `w_cnt` is hex. Adjective words
         * may carry `(a)`, `(p)` or `(ip)`, which are dropped; underscores become spaces.
         */
        fun parseSynset(line: String): Synset? {
            val bar = line.indexOf(" | ")
            val fields = (if (bar >= 0) line.substring(0, bar) else line).trim().split(' ')
            val count = fields.getOrNull(3)?.toIntOrNull(16) ?: return null
            if (fields.size < 4 + count * 2) return null
            val words =
                (0 until count)
                    .map { fields[4 + it * 2] }
                    .map { it.replace(ADJECTIVE_MARKER, "").replace('_', ' ') }
                    .distinct()
            val gloss = if (bar >= 0) line.substring(bar + 3).trim() else ""
            val (definition, examples) = splitGloss(gloss)
            return Synset(words, definition, examples)
        }

        private val ADJECTIVE_MARKER = Regex("""\((a|p|ip)\)$""")
        private val QUOTED = Regex("\"([^\"]*)\"")

        /**
         * The definition is the gloss before the first `; "`; the examples are the quoted parts
         * after it. Curly quotes inside examples are kept as they are.
         */
        fun splitGloss(gloss: String): Pair<String, List<String>> {
            val cut = gloss.indexOf("; \"")
            val definition = (if (cut >= 0) gloss.substring(0, cut) else gloss).trim()
            if (definition.startsWith("\"")) {
                // A gloss with examples only.
                return "" to QUOTED.findAll(gloss).map { it.groupValues[1].trim() }.toList()
            }
            val examples =
                if (cut < 0) emptyList()
                else
                    QUOTED.findAll(gloss.substring(cut))
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
            return definition to examples
        }

        fun stripAccents(word: String): String =
            Normalizer.normalize(word, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
