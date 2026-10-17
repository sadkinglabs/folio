package dev.sadking.folio.core.dictionary

import dev.sadking.folio.core.dictionary.PartOfSpeech.*
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A small dictionary written in the WNDB format, as Open English WordNet ships it: licence lines
 * that start with two spaces, byte-sorted index lines, and data lines found by byte offset.
 */
class WordNetDictionaryTest {
    private lateinit var dir: File
    private lateinit var dictionary: WordNetDictionary

    @Before
    fun write() {
        dir = Files.createTempDirectory("wordnet").toFile()
        WordNetFixture.write(dir)
        dictionary = WordNetDictionary(dir)
    }

    @After
    fun clean() {
        dictionary.close()
        dir.deleteRecursively()
    }

    @Test
    fun aNounWithItsSensesExamplesAndSynonyms() {
        val entries = dictionary.define("Dog")
        assertEquals(listOf(NOUN, VERB), entries.map { it.partOfSpeech })
        val noun = entries.first()
        assertEquals("dog", noun.headword)
        assertEquals(2, noun.senses.size)
        val first = noun.senses.first()
        assertEquals(
            "a member of the genus Canis that has been domesticated by man; occurs in many breeds",
            first.definition,
        )
        assertEquals(listOf("the dog barked all night"), first.examples)
        assertEquals(listOf("domestic dog", "Canis familiaris"), first.synonyms)
        assertEquals("a dull unattractive unpleasant girl or woman", noun.senses[1].definition)
        assertEquals(
            listOf("she got a reputation as a frump", "she's a real dog"),
            noun.senses[1].examples,
        )
    }

    @Test
    fun theFirstAndLastIndexLinesAreFound() {
        // 'aardvark' is the first line after the licence; 'zymurgy' is the last in the file.
        assertNotNull(dictionary.indexLine(NOUN, "aardvark"))
        assertNotNull(dictionary.indexLine(NOUN, "zymurgy"))
        assertEquals("aardvark", dictionary.define("aardvark").single().headword)
        assertEquals("zymurgy", dictionary.define("zymurgy").single().headword)
        // Neighbours of real keys are not matches.
        assertNull(dictionary.indexLine(NOUN, "do"))
        assertNull(dictionary.indexLine(NOUN, "dogs"))
        assertNull(dictionary.indexLine(NOUN, "aaa"))
        assertNull(dictionary.indexLine(NOUN, "zzz"))
        // A prefix of a longer lemma is not that lemma.
        assertNull(dictionary.indexLine(NOUN, "dog_day"))
        assertNotNull(dictionary.indexLine(NOUN, "dog_days"))
    }

    @Test
    fun everyIndexLineIsFoundByBinarySearch() {
        WordNetFixture.nounLemmas.forEach {
            assertNotNull("$it should be found", dictionary.indexLine(NOUN, it))
        }
    }

    @Test
    fun inflectedFormsAreLemmatised() {
        val running = dictionary.define("running")
        assertEquals(
            listOf("running" to NOUN, "run" to VERB),
            running.map { it.headword to it.partOfSpeech },
        )
        assertEquals("move fast by using one's feet", running[1].senses.first().definition)
        assertEquals(
            listOf("dog" to NOUN, "dog" to VERB),
            dictionary.define("dogs").map { it.headword to it.partOfSpeech },
        )
        assertEquals(
            listOf("run" to VERB),
            dictionary.define("ran").map { it.headword to it.partOfSpeech },
        )
    }

    @Test
    fun satellitesAreAdjectivesAndMarkersAreDropped() {
        val handy = dictionary.define("handy").single()
        assertEquals(ADJECTIVE, handy.partOfSpeech)
        assertEquals("easy to reach", handy.senses.single().definition)
        assertEquals(
            listOf("found a handy spot for the can opener"),
            handy.senses.single().examples,
        )
        // "ready_to_hand(p)" is shown as "ready to hand".
        assertEquals(listOf("ready to hand"), handy.senses.single().synonyms)
    }

    @Test
    fun utf8LinesKeepTheirByteOffsetsAndCurlyQuotes() {
        // The entry after a line with multi-byte characters is still found by its byte offset.
        val cafe = dictionary.define("café").single()
        assertEquals("café", cafe.headword)
        assertEquals(
            "a small restaurant where drinks and snacks are sold",
            cafe.senses.single().definition,
        )
        val zymurgy = dictionary.define("zymurgy").single()
        assertEquals(listOf("the ‘zymurgy’ of beer"), zymurgy.senses.single().examples)
        // A selection with an accent the dictionary does not have is looked up without it.
        assertEquals("naive", dictionary.define("naïve").single().headword)
    }

    @Test
    fun properNounsKeepTheirCase() {
        assertEquals("London", dictionary.define("london").single().headword)
    }

    @Test
    fun nothingForUnknownWordsOrLongSelections() {
        assertTrue(dictionary.define("qwertyuiop").isEmpty())
        assertTrue(dictionary.define("the dog barked all night long").isEmpty())
        assertTrue(dictionary.define("…").isEmpty())
    }

    @Test
    fun aMissingFolderGivesNothing() {
        WordNetDictionary(File(dir, "missing")).use { assertTrue(it.define("dog").isEmpty()) }
    }

    @Test
    fun glossesSplitIntoDefinitionAndExamples() {
        val (definition, examples) =
            WordNetDictionary.splitGloss(
                "wipe out the effect of something; \"The new tax cancels out my raise\"; \"The ‘A’ will cancel out the ‘C’\""
            )
        assertEquals("wipe out the effect of something", definition)
        assertEquals(
            listOf("The new tax cancels out my raise", "The ‘A’ will cancel out the ‘C’"),
            examples,
        )
        assertEquals("plain" to emptyList<String>(), WordNetDictionary.splitGloss("plain"))
    }
}

/** Writes the fixture's WNDB files, with real byte offsets in the index lines. */
internal object WordNetFixture {
    private val licence =
        (1..29).map { "  $it This software and database is being provided to you, the LICENSEE.  " }

    private class Synset(val type: String, val words: List<String>, val gloss: String)

    private val nouns =
        mapOf(
            "aardvark" to
                listOf(Synset("n", listOf("aardvark", "ant_bear"), "nocturnal burrowing mammal")),
            "café" to
                listOf(
                    Synset(
                        "n",
                        listOf("café", "coffee_shop"),
                        "a small restaurant where drinks and snacks are sold",
                    )
                ),
            "dog" to
                listOf(
                    Synset(
                        "n",
                        listOf("dog", "domestic_dog", "Canis_familiaris"),
                        "a member of the genus Canis that has been domesticated by man; occurs in many breeds; \"the dog barked all night\"",
                    ),
                    Synset(
                        "n",
                        listOf("frump", "dog"),
                        "a dull unattractive unpleasant girl or woman; \"she got a reputation as a frump\"; \"she's a real dog\"",
                    ),
                ),
            "dog_days" to
                listOf(
                    Synset(
                        "n",
                        listOf("dog_days", "canicule"),
                        "the hot period between early July and early September",
                    )
                ),
            "london" to
                listOf(
                    Synset(
                        "n",
                        listOf("London", "Greater_London"),
                        "the capital and largest city of England",
                    )
                ),
            "running" to
                listOf(
                    Synset(
                        "n",
                        listOf("running", "running_play"),
                        "the act of running; traveling on foot at a fast pace",
                    )
                ),
            "zymurgy" to
                listOf(
                    Synset(
                        "n",
                        listOf("zymurgy"),
                        "the branch of chemistry that deals with fermentation; \"the ‘zymurgy’ of beer\"",
                    )
                ),
        )
    val nounLemmas = nouns.keys.toList()

    private val verbs =
        mapOf(
            "dog" to
                listOf(Synset("v", listOf("dog", "chase"), "go after with the intent to catch")),
            "run" to listOf(Synset("v", listOf("run"), "move fast by using one's feet")),
        )

    private val adjectives =
        mapOf(
            "handy" to
                listOf(
                    Synset(
                        "s",
                        listOf("handy", "ready_to_hand(p)"),
                        "easy to reach; \"found a handy spot for the can opener\"",
                    )
                ),
            "naive" to
                listOf(
                    Synset(
                        "a",
                        listOf("naive(a)", "naif"),
                        "marked by or showing unaffected simplicity",
                    )
                ),
        )

    fun write(dir: File) {
        part(dir, "noun", nouns)
        part(dir, "verb", verbs)
        part(dir, "adj", adjectives)
        part(dir, "adv", emptyMap())
        File(dir, "noun.exc").writeText("geese goose\n")
        File(dir, "verb.exc").writeText("ran run\nrunning run\n")
        File(dir, "adj.exc").writeText("better good well\n")
        File(dir, "adv.exc").writeText("best well\n")
    }

    private fun part(dir: File, pos: String, entries: Map<String, List<Synset>>) {
        val data = StringBuilder()
        licence.forEach { data.append(it).append('\n') }
        val offsets = mutableMapOf<String, List<Long>>()
        // A synset's line starts with its own 8-digit byte offset, so it is built in two passes.
        entries.forEach { (lemma, synsets) ->
            offsets[lemma] = synsets.map { synset ->
                val offset = data.toString().toByteArray(Charsets.UTF_8).size.toLong()
                val words = synset.words.joinToString(" ") { "$it 0" }
                val count = synset.words.size.toString(16).padStart(2, '0')
                data.append(
                    "%08d 05 %s %s %s 000 | %s  \n"
                        .format(offset, synset.type, count, words, synset.gloss)
                )
                offset
            }
        }
        File(dir, "data.$pos").writeText(data.toString(), Charsets.UTF_8)
        val index = StringBuilder()
        licence.forEach { index.append(it).append('\n') }
        val lines = offsets.map { (lemma, at) ->
            "$lemma ${pos.first()} ${at.size} 0 ${at.size} 0 ${at.joinToString(" ") { "%08d".format(it) }}  "
        }
        // Sorted by bytes, as the C locale sorts them.
        lines
            .sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }
            .forEach { index.append(it).append('\n') }
        File(dir, "index.$pos").writeText(index.toString(), Charsets.UTF_8)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
