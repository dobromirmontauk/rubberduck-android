package com.montauk.voicecapture.tags

/**
 * One node of the vault's `tags.yaml` hierarchy (bead vn-edu.47) -- a
 * ULID-prefixed [id] that is permanent (rename/move never changes it, per
 * the vault's own tag-tree rules), a lowercase-kebab-case [name] (the leaf
 * label shown in chips), an optional [parentId] (null for a root), and a
 * one-sentence [description] used to help an LLM scorer decide whether a
 * spoken topic matches this node. [deprecated] nodes are still resolvable
 * (an old `tag_id` reference must never break) but are excluded from new
 * matching via [TagTree.activeNodes].
 */
data class TagTreeNode(
    val id: String,
    val name: String,
    val parentId: String?,
    val description: String,
    val deprecated: Boolean = false,
)

/**
 * An in-memory snapshot of `tags.yaml` -- pure Kotlin, no I/O, no Android
 * dependency. [EMPTY] represents "no vault configured" (bead vn-edu.47's
 * free-form-fallback case): every lookup on it behaves as if nothing
 * exists, which is exactly what a keyless/no-vault [TagScorer] wants.
 */
class TagTree(val nodes: List<TagTreeNode>) {
    private val byId: Map<String, TagTreeNode> = nodes.associateBy { it.id }

    val isEmpty: Boolean get() = nodes.isEmpty()

    fun node(id: String): TagTreeNode? = byId[id]

    /** Non-[TagTreeNode.deprecated] nodes, in file order -- what a scorer should be offered to match against. */
    fun activeNodes(): List<TagTreeNode> = nodes.filterNot { it.deprecated }

    /**
     * Slash-joined ancestor chain by [TagTreeNode.name], root-first, e.g.
     * `"work/mashgin"` for the `mashgin` node whose parent is `work` --
     * mirrors the vault's own Obsidian `#work/mashgin` tag convention (see
     * voice-vault/CLAUDE.md). A dangling or self-referential [parentId]
     * chain stops walking rather than looping forever or throwing; the
     * returned path is whatever prefix was resolvable.
     */
    fun path(id: String): String {
        val chain = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = byId[id]
        while (current != null && current.id !in visited) {
            visited += current.id
            chain += current.name
            current = current.parentId?.let { byId[it] }
        }
        return chain.asReversed().joinToString("/")
    }

    companion object {
        val EMPTY = TagTree(emptyList())
    }
}

/**
 * Tolerant hand-rolled parser for `tags.yaml`'s specific shape (a top-level
 * YAML sequence of maps, each with `id`/`name`/`parent`/`description` and an
 * optional `deprecated` flag) -- deliberately NOT a general YAML parser: no
 * flow-style collections, no multi-line scalars, no anchors/aliases. The
 * vault repo is a separate project that can evolve `tags.yaml`'s shape (new
 * keys, reordered fields, extra header comments) without this app's build
 * being in the loop, so this parser is forgiving by construction rather than
 * strict: unrecognized keys are ignored, an entry missing `id` or `name` is
 * dropped rather than failing the whole parse, and comments/blank lines are
 * skipped outright. See the file's own header comment in voice-vault for the
 * authoritative shape this mirrors.
 */
object TagTreeParser {
    fun parse(yamlText: String): TagTree {
        val nodes = mutableListOf<TagTreeNode>()
        var id: String? = null
        var name: String? = null
        var parent: String? = null
        var description = ""
        var deprecated = false
        var hasEntry = false

        fun flush() {
            val nodeId = id
            val nodeName = name
            if (hasEntry && nodeId != null && nodeName != null) {
                nodes += TagTreeNode(nodeId, nodeName, parent, description, deprecated)
            }
            hasEntry = false
            id = null
            name = null
            parent = null
            description = ""
            deprecated = false
        }

        for (rawLine in yamlText.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val isNewEntry = trimmed.startsWith("- ")
            val body = if (isNewEntry) trimmed.removePrefix("-").trim() else trimmed

            val colonIdx = body.indexOf(':')
            if (colonIdx < 0) continue
            val key = body.substring(0, colonIdx).trim()
            val value = body.substring(colonIdx + 1).trim().unquote()

            if (isNewEntry) {
                flush()
                hasEntry = true
            }
            if (!hasEntry) continue // a stray top-level key before any "- " marker is seen -- ignore

            when (key) {
                "id" -> id = value.takeIf { it.isNotEmpty() }
                "name" -> name = value.takeIf { it.isNotEmpty() }
                "parent" -> parent = value.takeUnless { it.isEmpty() || it == "null" || it == "~" }
                "description" -> description = value
                "deprecated" -> deprecated = value.equals("true", ignoreCase = true)
                else -> Unit // unknown key -- tolerant, ignore
            }
        }
        flush()
        return TagTree(nodes)
    }

    private fun String.unquote(): String =
        if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
            substring(1, length - 1)
        } else {
            this
        }
}
