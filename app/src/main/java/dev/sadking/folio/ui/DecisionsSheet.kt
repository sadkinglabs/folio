@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.sadking.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.HardcoverHolder
import dev.sadking.folio.data.MoveOffer
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.MatchCandidateView
import dev.sadking.folio.sync.MatchCheck
import dev.sadking.folio.sync.StatusQuestion

internal const val DECISIONS_SHEET_TAG = "decisions-sheet"

internal fun decisionOptionTag(questionId: String, option: String) = "decision-$questionId-$option"

internal fun matchPickTag(itemId: String, hardcoverId: Int) = "match-pick-$itemId-$hardcoverId"

internal fun matchNotOnHardcoverTag(itemId: String) = "match-not-on-hardcover-$itemId"

internal fun matchSearchTag(itemId: String) = "match-search-$itemId"

internal fun hardcoverAnswerTag(hardcoverId: Int, useHardcover: Boolean) =
    "hardcover-answer-$hardcoverId-${if (useHardcover) "hardcover" else "librillo"}"

internal fun hardcoverAnswerAllTag(useHardcover: Boolean) =
    "hardcover-answer-all-${if (useHardcover) "hardcover" else "librillo"}"

/**
 * One question an e-reader's sync left open, as the decisions sheet shows it. [answer] is the
 * option already chosen on the phone, waiting for the e-reader's next sync, or null.
 */
internal data class Decision(
    val ereaderId: String,
    val ereaderName: String?,
    val question: Question,
    val answer: String?,
)

/** Every open question of every e-reader: unanswered ones first, then the answered ones. */
internal fun decisions(ereaders: Collection<EreaderSync>): List<Decision> =
    ereaders
        .flatMap { e ->
            e.questions.map { q ->
                Decision(e.id, e.name.takeIf { it.isNotBlank() }, q, e.answers[q.id])
            }
        }
        .sortedBy { it.answer != null }

/**
 * "Needs your decision": the questions e-reader sync left open, with one button per answer; the
 * Hardcover matches Librillo was unsure of; and the books whose status changed on both Librillo and
 * Hardcover. An e-reader answer is kept on the phone and used at the e-reader's next sync; a
 * Hardcover answer is used at once.
 */
@Composable
internal fun DecisionsSheet(
    decisions: List<Decision>,
    checks: List<MatchCheck>,
    questions: List<StatusQuestion>,
    onAnswer: (Decision, String) -> Unit,
    onPick: (MatchCheck, MatchCandidateView) -> Unit,
    onNotOnHardcover: (MatchCheck) -> Unit,
    onSearch: (MatchCheck) -> Unit,
    onHardcoverAnswer: (StatusQuestion, Boolean) -> Unit,
    onAnswerAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    offer: MoveOffer? = null,
    onMove: (MoveOffer) -> Unit = {},
    onKeep: () -> Unit = {},
) {
    FolioSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DecisionsContent(
            decisions,
            checks,
            questions,
            onAnswer,
            onPick,
            onNotOnHardcover,
            onSearch,
            onHardcoverAnswer,
            onAnswerAll,
            offer,
            onMove,
            onKeep,
        )
    }
}

/**
 * The sheet's content, so previews and tests can show it without a sheet window. In order: the
 * e-reader's questions, "Check these matches", then the Hardcover status questions. For a status
 * question, true means Hardcover is right.
 */
@Composable
internal fun DecisionsContent(
    decisions: List<Decision>,
    checks: List<MatchCheck> = emptyList(),
    questions: List<StatusQuestion> = emptyList(),
    onAnswer: (Decision, String) -> Unit = { _, _ -> },
    onPick: (MatchCheck, MatchCandidateView) -> Unit = { _, _ -> },
    onNotOnHardcover: (MatchCheck) -> Unit = {},
    onSearch: (MatchCheck) -> Unit = {},
    onHardcoverAnswer: (StatusQuestion, Boolean) -> Unit = { _, _ -> },
    onAnswerAll: (Boolean) -> Unit = {},
    offer: MoveOffer? = null,
    onMove: (MoveOffer) -> Unit = {},
    onKeep: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = FolioSpace.section, end = FolioSpace.section, bottom = 32.dp)
            .testTag(DECISIONS_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(FolioSpace.item),
    ) {
        Text(
            stringResource(R.string.m9sync_decisions_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (decisions.isEmpty() && checks.isEmpty() && questions.isEmpty())
            Text(
                stringResource(R.string.m9sync_decisions_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (decisions.isNotEmpty()) {
            val name =
                decisions.firstNotNullOfOrNull { it.ereaderName }
                    ?: stringResource(R.string.m9sync_your_ereader)
            Text(
                stringResource(R.string.m9sync_decisions_body, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            decisions.forEach { DecisionCard(it, onAnswer) }
        }
        if (checks.isNotEmpty()) {
            SectionHeading(stringResource(R.string.hc10_checks_title))
            checks.forEach { check ->
                MatchCheckCard(
                    check,
                    onPick,
                    onNotOnHardcover,
                    onSearch,
                    offer?.takeIf { it.itemId == check.itemId },
                    onMove,
                    onKeep,
                )
            }
        }
        if (questions.isNotEmpty()) {
            SectionHeading(stringResource(R.string.hc10_questions_title))
            if (questions.size > 1)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                    verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
                ) {
                    OutlinedButton(
                        onClick = { onAnswerAll(true) },
                        modifier = Modifier.testTag(hardcoverAnswerAllTag(true)),
                    ) {
                        Text(stringResource(R.string.hc10_all_hardcover))
                    }
                    OutlinedButton(
                        onClick = { onAnswerAll(false) },
                        modifier = Modifier.testTag(hardcoverAnswerAllTag(false)),
                    ) {
                        Text(stringResource(R.string.hc10_all_librillo))
                    }
                }
            questions.forEach { StatusQuestionCard(it, onHardcoverAnswer) }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = FolioSpace.small).semantics { heading() },
    )
}

/** One question: its sentence, then a button per answer. Once answered, the choice and a note. */
@Composable
private fun DecisionCard(decision: Decision, onAnswer: (Decision, String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(decision.question.text, style = MaterialTheme.typography.bodyLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                decision.question.options.forEach { option ->
                    val chosen = option == decision.answer
                    val tag = Modifier.testTag(decisionOptionTag(decision.question.id, option))
                    if (chosen)
                        Button(onClick = {}, modifier = tag) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(FolioSpace.small))
                            Text(option)
                        }
                    else
                        OutlinedButton(onClick = { onAnswer(decision, option) }, modifier = tag) {
                            Text(option)
                        }
                }
            }
            if (decision.answer != null)
                Text(
                    stringResource(
                        R.string.m9sync_decision_answered,
                        decision.ereaderName ?: stringResource(R.string.m9sync_your_ereader),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/**
 * A book Librillo could not match for sure: its title and author, then up to three Hardcover books
 * with cover, title, author and year, each with "This one". A Hardcover book already matched to
 * another book says so ("Already matched to …"); picking it asks whether to move the match
 * ([offer]). Under them: Not on Hardcover, which Librillo remembers, and Search Hardcover for a
 * book that is not among them.
 */
@Composable
private fun MatchCheckCard(
    check: MatchCheck,
    onPick: (MatchCheck, MatchCandidateView) -> Unit,
    onNotOnHardcover: (MatchCheck) -> Unit,
    onSearch: (MatchCheck) -> Unit,
    offer: MoveOffer?,
    onMove: (MoveOffer) -> Unit,
    onKeep: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(check.title, style = MaterialTheme.typography.titleMedium)
                usableAuthors(check.authors)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.hc10_check_question),
                style = MaterialTheme.typography.bodyLarge,
            )
            check.candidates.forEach { candidate ->
                CandidateRow(check.itemId, candidate, check.held[candidate.hardcoverId]) {
                    onPick(check, candidate)
                }
                if (offer?.hardcoverId == candidate.hardcoverId)
                    MoveMatchPrompt(offer, enabled = true, onMove = onMove, onKeep = onKeep)
                if (candidate != check.candidates.last()) HorizontalDivider()
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                TextButton(
                    onClick = { onNotOnHardcover(check) },
                    modifier = Modifier.testTag(matchNotOnHardcoverTag(check.itemId)),
                ) {
                    Text(stringResource(R.string.hc10_not_on_hardcover))
                }
                TextButton(
                    onClick = { onSearch(check) },
                    modifier = Modifier.testTag(matchSearchTag(check.itemId)),
                ) {
                    Text(stringResource(R.string.hc10_search))
                }
            }
        }
    }
}

/** Authors for a line of text, or null when there is none worth showing. */
private fun usableAuthors(authors: List<String>): String? =
    authors.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }

/** "Ursula K. Le Guin · 1969"; the author alone, or the year alone, when that is all there is. */
@Composable
internal fun candidateLine(candidate: MatchCandidateView): String? {
    val authors = usableAuthors(candidate.authors)
    val year = candidate.releaseYear
    return when {
        authors != null && year != null ->
            stringResource(R.string.hc10_candidate_line, authors, year)
        authors != null -> authors
        year != null -> year.toString()
        else -> null
    }
}

/**
 * One Hardcover book offered for a match: its cover (a drawn one when Hardcover had none), title,
 * author and year, and "This one". At large text the button goes under the words.
 */
@Composable
private fun CandidateRow(
    itemId: String,
    candidate: MatchCandidateView,
    held: HardcoverHolder?,
    onPick: () -> Unit,
) {
    val stacked = LocalDensity.current.fontScale > 1.5f
    val button: @Composable () -> Unit = {
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.testTag(matchPickTag(itemId, candidate.hardcoverId)),
        ) {
            Text(stringResource(R.string.hc10_this_one))
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(FolioSpace.gap),
        verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically,
    ) {
        Cover(
            Book(
                id = "hardcover-${candidate.hardcoverId}",
                title = candidate.title,
                authors = "[]",
                originalPath = "",
                currentPath = "",
                originalHash = "",
                currentHash = "",
                coverPath = candidate.coverPath,
            ),
            // The title is read out beside it; the cover adds nothing for a screen reader.
            Modifier.width(48.dp).aspectRatio(FolioSpace.COVER_RATIO).clearAndSetSemantics {},
            thumbnail = true,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
            candidateLine(candidate)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (held != null)
                Text(
                    heldText(held),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            if (stacked) button()
        }
        if (!stacked) button()
    }
}

/** Hardcover's own name for a status id: "Read", "Did Not Finish". */
@Composable
internal fun hardcoverStatusName(status: Int): String =
    when (status) {
        1 -> stringResource(R.string.hc10_status_want_to_read)
        2 -> stringResource(R.string.hc10_status_currently_reading)
        3 -> stringResource(R.string.hc10_status_read)
        4 -> stringResource(R.string.hc10_status_paused)
        5 -> stringResource(R.string.hc10_status_did_not_finish)
        else -> status.toString()
    }

/**
 * "Home: Hardcover says Read, Librillo says Reading. Which is right?" with one button per side.
 * When Hardcover no longer has the book: "Leave it off Hardcover" (Hardcover is right) or "Put it
 * back" (Librillo is right).
 */
@Composable
private fun StatusQuestionCard(
    question: StatusQuestion,
    onAnswer: (StatusQuestion, Boolean) -> Unit,
) {
    val librillo = libraryStatusLabel(question.librilloStatus)
    val gone = question.hardcoverStatus == 0
    Surface(
        Modifier.fillMaxWidth(),
        shape = FolioSpace.panelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(FolioSpace.item),
            verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
        ) {
            Text(
                if (gone) stringResource(R.string.hc10_question_gone, question.title, librillo)
                else
                    stringResource(
                        R.string.hc10_question,
                        question.title,
                        hardcoverStatusName(question.hardcoverStatus),
                        librillo,
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
            ) {
                OutlinedButton(
                    onClick = { onAnswer(question, true) },
                    modifier = Modifier.testTag(hardcoverAnswerTag(question.hardcoverId, true)),
                ) {
                    Text(
                        if (gone) stringResource(R.string.hc10_leave_off)
                        else
                            stringResource(
                                R.string.hc10_use_hardcover,
                                hardcoverStatusName(question.hardcoverStatus),
                            )
                    )
                }
                OutlinedButton(
                    onClick = { onAnswer(question, false) },
                    modifier = Modifier.testTag(hardcoverAnswerTag(question.hardcoverId, false)),
                ) {
                    Text(
                        if (gone) stringResource(R.string.hc10_put_back)
                        else stringResource(R.string.hc10_use_librillo, librillo)
                    )
                }
            }
        }
    }
}
