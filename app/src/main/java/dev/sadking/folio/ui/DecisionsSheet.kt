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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sadking.folio.R
import dev.sadking.folio.core.sync.Question
import dev.sadking.folio.data.HardcoverObservation
import dev.sadking.folio.data.WishBook
import dev.sadking.folio.sync.EreaderSync
import dev.sadking.folio.sync.FinishPrompt

internal const val DECISIONS_SHEET_TAG = "decisions-sheet"

internal fun decisionOptionTag(questionId: String, option: String) = "decision-$questionId-$option"

internal fun finishPromptTag(bookId: String, read: Boolean) =
    "finish-prompt-$bookId-${if (read) "read" else "later"}"

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
 * The finish prompts Hardcover has settled since: the phone now sees the read for that book as
 * something other than Currently Reading for the linked account (finished in the sheet, or on
 * Hardcover itself). Their book ids, to be dismissed. A book with no observation stays.
 */
internal fun settledPrompts(
    prompts: List<FinishPrompt>,
    wishes: List<WishBook>,
    observations: List<HardcoverObservation>,
    accountId: Int?,
): List<String> =
    prompts
        .filter { prompt ->
            val hardcoverId =
                wishes
                    .firstOrNull { it.linkedBookId == prompt.bookId && it.hardcoverId != null }
                    ?.hardcoverId
            val seen = observations.firstOrNull {
                it.accountId == accountId && it.hardcoverId == hardcoverId
            }
            hardcoverId != null && seen != null && seen.status != HARDCOVER_CURRENTLY_READING
        }
        .map { it.bookId }

private const val HARDCOVER_CURRENTLY_READING = 2

/**
 * "Needs your decision": the questions sync left open, with one button per answer, and the
 * Hardcover finish prompts. An answer is kept on the phone and used at the e-reader's next sync.
 */
@Composable
internal fun DecisionsSheet(
    decisions: List<Decision>,
    prompts: List<FinishPrompt>,
    onAnswer: (Decision, String) -> Unit,
    onMarkRead: (FinishPrompt) -> Unit,
    onNotNow: (FinishPrompt) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = FolioSpace.sheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DecisionsContent(decisions, prompts, onAnswer, onMarkRead, onNotNow)
    }
}

/** The sheet's content, so previews and tests can show it without a sheet window. */
@Composable
internal fun DecisionsContent(
    decisions: List<Decision>,
    prompts: List<FinishPrompt>,
    onAnswer: (Decision, String) -> Unit,
    onMarkRead: (FinishPrompt) -> Unit,
    onNotNow: (FinishPrompt) -> Unit,
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
        )
        if (decisions.isEmpty() && prompts.isEmpty())
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
        prompts.forEach { FinishPromptCard(it, onMarkRead, onNotNow) }
    }
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

/** "You finished Home. Mark it as read on Hardcover?" with Mark as read and Not now. */
@Composable
private fun FinishPromptCard(
    prompt: FinishPrompt,
    onMarkRead: (FinishPrompt) -> Unit,
    onNotNow: (FinishPrompt) -> Unit,
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
            Text(
                stringResource(R.string.m9sync_finish_prompt, prompt.title),
                style = MaterialTheme.typography.bodyLarge,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FolioSpace.small),
                verticalArrangement = Arrangement.spacedBy(FolioSpace.small),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onMarkRead(prompt) },
                    modifier = Modifier.testTag(finishPromptTag(prompt.bookId, read = true)),
                ) {
                    Text(stringResource(R.string.m9sync_finish_mark))
                }
                TextButton(
                    onClick = { onNotNow(prompt) },
                    modifier = Modifier.testTag(finishPromptTag(prompt.bookId, read = false)),
                ) {
                    Text(stringResource(R.string.m9sync_finish_not_now))
                }
            }
        }
    }
}
