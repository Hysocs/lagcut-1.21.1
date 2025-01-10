package com.lagcut.utils

import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.HoverEvent
import net.minecraft.text.ClickEvent
import net.minecraft.util.Formatting
import kotlin.math.roundToInt

object MiniMessageHelper {
    // Existing color definitions remain the same
    private val COLORS = mapOf(
        // Original colors
        "black" to 0x000000,
        "dark_blue" to 0x0000AA,
        "dark_green" to 0x00AA00,
        "dark_aqua" to 0x00AAAA,
        "dark_red" to 0xAA0000,
        "dark_purple" to 0xAA00AA,
        "gold" to 0xFFAA00,
        "gray" to 0xAAAAAA,
        "dark_gray" to 0x555555,
        "blue" to 0x5555FF,
        "green" to 0x55FF55,
        "aqua" to 0x55FFFF,
        "red" to 0xFF5555,
        "light_purple" to 0xFF55FF,
        "yellow" to 0xFFFF55,
        "white" to 0xFFFFFF,

        // Blues
        "navy" to 0x000080,
        "royal_blue" to 0x4169E1,
        "sky_blue" to 0x87CEEB,
        "azure" to 0xF0FFFF,
        "cornflower_blue" to 0x6495ED,
        "steel_blue" to 0x4682B4,
        "dodger_blue" to 0x1E90FF,
        "deep_sky_blue" to 0x00BFFF,
        "powder_blue" to 0xB0E0E6,

        // Greens
        "forest_green" to 0x228B22,
        "lime" to 0x00FF00,
        "olive" to 0x808000,
        "spring_green" to 0x00FF7F,
        "sea_green" to 0x2E8B57,
        "emerald" to 0x50C878,
        "mint" to 0x98FF98,
        "chartreuse" to 0x7FFF00,
        "lawn_green" to 0x7CFC00,

        // Reds and Pinks
        "crimson" to 0xDC143C,
        "salmon" to 0xFA8072,
        "coral" to 0xFF7F50,
        "tomato" to 0xFF6347,
        "fire_brick" to 0xB22222,
        "maroon" to 0x800000,
        "pink" to 0xFFC0CB,
        "deep_pink" to 0xFF1493,
        "hot_pink" to 0xFF69B4,

        // Purples
        "indigo" to 0x4B0082,
        "violet" to 0xEE82EE,
        "orchid" to 0xDA70D6,
        "magenta" to 0xFF00FF,
        "medium_purple" to 0x9370DB,
        "lavender" to 0xE6E6FA,
        "plum" to 0xDDA0DD,

        // Browns and Oranges
        "brown" to 0xA52A2A,
        "chocolate" to 0xD2691E,
        "sienna" to 0xA0522D,
        "saddle_brown" to 0x8B4513,
        "peru" to 0xCD853F,
        "tan" to 0xD2B48C,
        "orange" to 0xFFA500,
        "dark_orange" to 0xFF8C00,
        "orange_red" to 0xFF4500,

        // Yellows
        "khaki" to 0xF0E68C,
        "golden_rod" to 0xDAA520,
        "light_yellow" to 0xFFFFE0,
        "lemon_chiffon" to 0xFFFACD,

        // Grays and Neutrals
        "silver" to 0xC0C0C0,
        "dim_gray" to 0x696969,
        "slate_gray" to 0x708090,
        "light_gray" to 0xD3D3D3,
        "gainsboro" to 0xDCDCDC,
        "ghost_white" to 0xF8F8FF,

        // Cyan/Turquoise
        "cyan" to 0x00FFFF,
        "turquoise" to 0x40E0D0,
        "teal" to 0x008080,
        "light_cyan" to 0xE0FFFF,
        "cadet_blue" to 0x5F9EA0,

        // Misc
        "ivory" to 0xFFFFF0,
        "beige" to 0xF5F5DC,
        "wheat" to 0xF5DEB3,
        "bisque" to 0xFFE4C4,
        "antique_white" to 0xFAEBD7,
        "moccasin" to 0xFFE4B5,
        "slate_blue" to 0x6A5ACD,
        "rebecca_purple" to 0x663399,
        "midnight_blue" to 0x191970
    )

    // Existing interfaces and sealed classes remain the same
    interface NodeContainer {
        val children: MutableList<ParseNode>
    }

    sealed class ParseNode {
        data class TextNode(val text: String) : ParseNode()
        data class ColorNode(
            val color: Int,
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
        data class GradientNode(
            val startColor: Int,
            val endColor: Int,
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
        data class FormatNode(
            val format: Formatting,
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
        data class HoverNode(
            val hoverText: String,
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
        data class ClickNode(
            val action: ClickEvent.Action,
            val value: String,
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
        data class RainbowNode(
            val phase: Float = 0f,
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
        data class RootNode(
            override val children: MutableList<ParseNode> = mutableListOf()
        ) : ParseNode(), NodeContainer
    }

    fun parse(message: String): MutableText = renderNode(parseRecursive(message, 0, null).first)

    private fun parseRecursive(message: String, startIndex: Int, parentTag: String?): Pair<ParseNode, Int> {
        val root = createNode(parentTag)
        val childList = when (root) {
            is ParseNode.TextNode -> mutableListOf()
            is ParseNode.ColorNode -> root.children
            is ParseNode.GradientNode -> root.children
            is ParseNode.FormatNode -> root.children
            is ParseNode.HoverNode -> root.children
            is ParseNode.ClickNode -> root.children
            is ParseNode.RainbowNode -> root.children
            is ParseNode.RootNode -> root.children
        }

        // Updated regex to handle the new click format and make tags more specific
        val tagRegex = Regex("""<(?:(?:/(?:gradient|bold|italic|underlined|strikethrough|obfuscated|hover|click|color|rainbow|[a-zA-Z]+)>)|(?:(?:gradient:#[A-Fa-f0-9]{6}(?::#[A-Fa-f0-9]{6})?|bold|italic|underlined|strikethrough|obfuscated|hover:[^>]+|click:(?:run_command|suggest_command|open_url|copy_to_clipboard):[^>]+|color:#[A-Fa-f0-9]{6}|rainbow(?::\d+(?:\.\d+)?)?|[a-zA-Z]+)>))""")

        var i = startIndex
        val sb = StringBuilder()

        while (i < message.length) {
            val match = tagRegex.find(message.substring(i)) ?: break
            val matchStart = match.range.first + i

            if (i < matchStart) {
                sb.append(message.substring(i, matchStart))
            }

            if (sb.isNotEmpty()) {
                childList.add(ParseNode.TextNode(sb.toString()))
                sb.clear()
            }

            val tag = match.value
            i = matchStart + tag.length

            if (tag.startsWith("</")) {
                val closeTag = tag.substring(2, tag.length - 1)
                if (isMatchingCloseTag(parentTag, closeTag)) {
                    return Pair(root, i)
                }
            } else {
                val openTag = tag.substring(1, tag.length - 1)
                val (childNode, newIndex) = parseRecursive(message, i, openTag)
                childList.add(childNode)
                i = newIndex
            }
        }

        if (i < message.length) {
            sb.append(message.substring(i))
        }

        if (sb.isNotEmpty()) {
            childList.add(ParseNode.TextNode(sb.toString()))
        }

        return Pair(root, i)
    }

    private fun createNode(tag: String?): ParseNode = when {
        tag == null -> ParseNode.RootNode()
        tag == "bold" -> ParseNode.FormatNode(Formatting.BOLD)
        tag == "italic" -> ParseNode.FormatNode(Formatting.ITALIC)
        tag == "underlined" -> ParseNode.FormatNode(Formatting.UNDERLINE)
        tag == "strikethrough" -> ParseNode.FormatNode(Formatting.STRIKETHROUGH)
        tag == "obfuscated" -> ParseNode.FormatNode(Formatting.OBFUSCATED)
        tag.startsWith("hover:") -> ParseNode.HoverNode(tag.substringAfter("hover:"))
        tag.startsWith("click:") -> {
            val parts = tag.split(":", limit = 3)
            if (parts.size == 3) {
                val action = when (parts[1]) {
                    "run_command" -> ClickEvent.Action.RUN_COMMAND
                    "suggest_command" -> ClickEvent.Action.SUGGEST_COMMAND
                    "open_url" -> ClickEvent.Action.OPEN_URL
                    "copy_to_clipboard" -> ClickEvent.Action.COPY_TO_CLIPBOARD
                    else -> ClickEvent.Action.RUN_COMMAND
                }
                ParseNode.ClickNode(action, parts[2])
            } else ParseNode.RootNode()
        }
        tag.startsWith("gradient:") -> {
            val colors = tag.split(":").drop(1)
            ParseNode.GradientNode(
                decodeColor(colors[0]),
                if (colors.size > 1) decodeColor(colors[1]) else decodeColor(colors[0])
            )
        }
        tag.startsWith("rainbow") -> {
            val phase = tag.substringAfter("rainbow:", "0")
                .toFloatOrNull() ?: 0f
            ParseNode.RainbowNode(phase)
        }
        tag.startsWith("color:") -> ParseNode.ColorNode(decodeColor(tag.substringAfter("color:")))
        COLORS.containsKey(tag.lowercase()) -> {
            // Handle named colors including those with underscores
            ParseNode.ColorNode(COLORS[tag.lowercase()]!!)
        }
        else -> ParseNode.RootNode()
    }

    private fun isMatchingCloseTag(openTag: String?, closeTag: String): Boolean {
        if (openTag == null) return false
        return when {
            closeTag == "gradient" && openTag.startsWith("gradient:") -> true
            closeTag == "color" && (openTag.startsWith("color:") || COLORS.containsKey(openTag.lowercase())) -> true
            closeTag == "rainbow" && openTag.startsWith("rainbow") -> true
            closeTag == "click" && openTag.startsWith("click:") -> true
            closeTag == openTag -> true
            else -> false
        }
    }

    private fun renderNode(node: ParseNode): MutableText = when (node) {
        is ParseNode.TextNode -> Text.literal(node.text)
        is ParseNode.ColorNode -> {
            val combined = Text.literal("")
            node.children.forEach { child ->
                val rendered = renderNode(child)
                forEachLeaf(rendered) { leaf ->
                    leaf.style = leaf.style.withColor(node.color)
                }
                combined.append(rendered)
            }
            combined
        }
        is ParseNode.GradientNode -> {
            val renderedChildren = node.children.map { renderNode(it) }
            applyGradient(collectLeaves(renderedChildren), node.startColor, node.endColor)
        }
        is ParseNode.FormatNode -> {
            val combined = Text.literal("")
            node.children.forEach { child ->
                val rendered = renderNode(child)
                forEachLeaf(rendered) { leaf ->
                    leaf.style = when (node.format) {
                        Formatting.BOLD -> leaf.style.withBold(true)
                        Formatting.ITALIC -> leaf.style.withItalic(true)
                        Formatting.UNDERLINE -> leaf.style.withUnderline(true)
                        Formatting.STRIKETHROUGH -> leaf.style.withStrikethrough(true)
                        Formatting.OBFUSCATED -> leaf.style.withObfuscated(true)
                        else -> leaf.style
                    }
                }
                combined.append(rendered)
            }
            combined
        }
        is ParseNode.HoverNode -> {
            val combined = Text.literal("")
            node.children.forEach { child ->
                val rendered = renderNode(child)
                forEachLeaf(rendered) { leaf ->
                    leaf.style = leaf.style.withHoverEvent(
                        HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(node.hoverText))
                    )
                }
                combined.append(rendered)
            }
            combined
        }
        is ParseNode.ClickNode -> {
            val combined = Text.literal("")
            node.children.forEach { child ->
                val rendered = renderNode(child)
                forEachLeaf(rendered) { leaf ->
                    leaf.style = leaf.style.withClickEvent(
                        ClickEvent(node.action, node.value)
                    )
                }
                combined.append(rendered)
            }
            combined
        }
        is ParseNode.RainbowNode -> {
            val renderedChildren = node.children.map { renderNode(it) }
            applyRainbow(collectLeaves(renderedChildren), node.phase)
        }
        is ParseNode.RootNode -> {
            val combined = Text.literal("")
            node.children.forEach { child ->
                combined.append(renderNode(child))
            }
            combined
        }
    }

    // Remaining utility functions stay the same
    private fun collectLeaves(texts: List<MutableText>): List<Pair<Char, Style>> {
        val result = mutableListOf<Pair<Char, Style>>()
        texts.forEach { text ->
            forEachLeaf(text) { leaf ->
                leaf.string.forEach { c -> result.add(c to leaf.style) }
            }
        }
        return result
    }

    private fun applyGradient(leaves: List<Pair<Char, Style>>, startColor: Int, endColor: Int): MutableText {
        val result = Text.literal("")
        val length = leaves.size.coerceAtLeast(1)

        leaves.forEachIndexed { i, (char, style) ->
            val ratio = i.toFloat() / (length - 1)
            val r = interpolate((startColor shr 16) and 0xFF, (endColor shr 16) and 0xFF, ratio)
            val g = interpolate((startColor shr 8) and 0xFF, (endColor shr 8) and 0xFF, ratio)
            val b = interpolate(startColor and 0xFF, endColor and 0xFF, ratio)
            val color = (r shl 16) or (g shl 8) or b

            result.append(Text.literal(char.toString()).setStyle(style.withColor(color)))
        }
        return result
    }

    private fun applyRainbow(leaves: List<Pair<Char, Style>>, phase: Float): MutableText {
        val result = Text.literal("")
        val length = leaves.size.coerceAtLeast(1)

        leaves.forEachIndexed { i, (char, style) ->
            val hue = (i.toFloat() / length + phase) % 1f
            val rgb = hsvToRgb(hue, 1f, 1f)
            val color = (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]

            result.append(Text.literal(char.toString()).setStyle(style.withColor(color)))
        }
        return result
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): IntArray {
        val i = (h * 6).toInt()
        val f = h * 6 - i
        val p = v * (1 - s)
        val q = v * (1 - f * s)
        val t = v * (1 - (1 - f) * s)

        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }

        return intArrayOf(
            (r * 255).roundToInt(),
            (g * 255).roundToInt(),
            (b * 255).roundToInt()
        )
    }

    private fun interpolate(start: Int, end: Int, ratio: Float): Int =
        (start + (end - start) * ratio).toInt()

    private fun decodeColor(hex: String): Int = Integer.decode(hex)

    private fun forEachLeaf(text: MutableText, block: (MutableText) -> Unit) {
        if (text.siblings.isEmpty()) {
            block(text)
        } else {
            text.siblings.forEach { sibling ->
                if (sibling is MutableText) {
                    forEachLeaf(sibling, block)
                }
            }
        }
    }
}