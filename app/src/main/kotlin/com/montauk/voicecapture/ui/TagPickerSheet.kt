package com.montauk.voicecapture.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.tags.TagTree
import com.montauk.voicecapture.tags.TagTreeNode

/**
 * Searchable tag-tree picker bottom sheet (bead asn-45m) shared by the
 * recording screen's trailing (+) chip (add) and tapping an existing chip's
 * body (swap) -- [onPick] doesn't distinguish the two; the caller ([RecordingScreen])
 * decides whether a pick means "add" or "swap [some specific chip]" based on
 * which affordance opened the sheet.
 *
 * Anchored to the same [TagTree] the app already loads for scoring (bead
 * vn-edu.47) -- [tree] is expected to already be resolved (e.g. via
 * [com.montauk.voicecapture.VoiceCaptureApp.currentTagTree]) by the caller,
 * not fetched by this composable itself. [TagTree.EMPTY] (no vault
 * configured, or nothing cached yet) degrades to "search box with no
 * results but the free-form Add row" rather than an error state -- matches
 * every other keyless/no-vault fallback in this app.
 *
 * A typed [query] that doesn't exactly match any node's name or full path
 * gets an extra "Add "<query>"" row at the bottom of the results (bead:
 * "trailing (+) chip adds a tag manually" -- manual here means free text is
 * always an option, not just tree nodes) that calls [onPick] with a null
 * `tagId`, i.e. a free-form tag exactly like a legacy no-vault tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(tree: TagTree, onPick: (tag: String, tagId: String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(tree, query) {
        val q = query.trim()
        tree.activeNodes().filter { node -> q.isEmpty() || tree.path(node.id).contains(q, ignoreCase = true) }
    }
    val exactMatchExists = remember(matches, query) {
        val q = query.trim()
        q.isNotEmpty() && matches.any { it.name.equals(q, ignoreCase = true) || tree.path(it.id).equals(q, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().testTag(TAG_PICKER_SHEET_TEST_TAG)) {
            Text(text = "Pick a tag", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search tags") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TAG_PICKER_SEARCH_FIELD_TEST_TAG),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(matches) { node ->
                    TagPickerResultRow(label = tree.path(node.id), onClick = { onPick(node.name, node.id) })
                }
                if (query.isNotBlank() && !exactMatchExists) {
                    item {
                        TagPickerResultRow(
                            label = "Add \"${query.trim()}\"",
                            onClick = { onPick(query.trim(), null) },
                            testTag = TAG_PICKER_ADD_FREEFORM_TEST_TAG,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TagPickerResultRow(label: String, onClick: () -> Unit, testTag: String = TAG_PICKER_RESULT_ROW_TEST_TAG) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 14.dp),
    )
}

/** Test-only anchors for [TagPickerSheet]'s parts. */
const val TAG_PICKER_SHEET_TEST_TAG = "tag_picker_sheet"
const val TAG_PICKER_SEARCH_FIELD_TEST_TAG = "tag_picker_search_field"
const val TAG_PICKER_RESULT_ROW_TEST_TAG = "tag_picker_result_row"
const val TAG_PICKER_ADD_FREEFORM_TEST_TAG = "tag_picker_add_freeform_row"

/** Exposed for previews/tests that want a small fixed tree without hitting [TagTree.EMPTY]'s no-results path. */
internal fun previewTagTree(): TagTree = TagTree(
    listOf(
        TagTreeNode(id = "t_work", name = "work", parentId = null, description = "Professional life."),
        TagTreeNode(id = "t_mashgin", name = "mashgin", parentId = "t_work", description = "Mashgin-specific work."),
        TagTreeNode(id = "t_home", name = "home", parentId = null, description = "House projects."),
    ),
)
