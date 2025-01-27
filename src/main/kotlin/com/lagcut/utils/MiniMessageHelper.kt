package com.lagcut.utils

import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.HoverEvent
import net.minecraft.text.ClickEvent
import net.minecraft.util.Formatting
import kotlin.math.roundToInt

object MiniMessageHelper {
    private val COLORS = mapOf(
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
        "white" to 0xFFFFFF
    )

    private data class Tag(
        val name: String,
        val params: List<String>,
        val start: Int,
        val end: Int,
        val content: String,
        val originalStart: Int = start,  // Added to track original position
        val originalEnd: Int = end       // Added to track original position
    )

    fun parse(message: String): MutableText {
        return parseSection(message, 0, message.length)
    }

    private fun parseSection(message: String, start: Int, end: Int): MutableText {
        val result = Text.literal("")
        var currentPos = start
        val tags = findTags(message, start, end)

        for (tag in tags) {
            // Add text before tag
            if (tag.start > currentPos) {
                result.append(Text.literal(message.substring(currentPos, tag.start)))
            }

            // Parse the content recursively to handle nested tags
            val contentText = parseSection(tag.content, 0, tag.content.length)

            // Apply formatting based on tag type
            val formattedText = when {
                tag.name == "bold" -> {
                    contentText.setStyle(contentText.style.withBold(true))
                }
                tag.name == "hover" -> {
                    contentText.setStyle(contentText.style.withHoverEvent(
                        HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(tag.params[0]))
                    ))
                }
                tag.name == "gradient" && tag.params.size >= 2 -> {
                    createGradient(tag.content, decodeColor(tag.params[0]), decodeColor(tag.params[1]), findNestedTags(tag.content))
                }
                COLORS.containsKey(tag.name.lowercase()) -> {
                    contentText.setStyle(contentText.style.withColor(COLORS[tag.name.lowercase()]!!))
                }
                else -> contentText
            }

            result.append(formattedText)
            currentPos = tag.end
        }

        // Add remaining text
        if (currentPos < end) {
            result.append(Text.literal(message.substring(currentPos, end)))
        }

        return result
    }

    private fun findNestedTags(content: String): List<Tag> {
        return findTags(content, 0, content.length)
    }

    private fun findTags(message: String, start: Int, end: Int): List<Tag> {
        val tags = mutableListOf<Tag>()
        var pos = start

        while (pos < end) {
            val tagStart = message.indexOf('<', pos)
            if (tagStart == -1 || tagStart >= end) break

            val tagEnd = message.indexOf('>', tagStart)
            if (tagEnd == -1) break

            val tagContent = message.substring(tagStart + 1, tagEnd)
            if (!tagContent.startsWith('/')) {
                val tagParts = tagContent.split(':')
                val tagName = tagParts[0]
                val tagParams = tagParts.drop(1)

                // Find closing tag
                val closingTag = "</$tagName>"
                val contentEnd = message.indexOf(closingTag, tagEnd + 1)
                if (contentEnd != -1) {
                    tags.add(Tag(
                        name = tagName,
                        params = tagParams,
                        start = tagStart,
                        end = contentEnd + closingTag.length,
                        content = message.substring(tagEnd + 1, contentEnd),
                        originalStart = tagStart,
                        originalEnd = contentEnd
                    ))
                    pos = contentEnd + closingTag.length
                    continue
                }
            }
            pos = tagEnd + 1
        }

        return tags
    }

    private fun createGradient(text: String, startColor: Int, endColor: Int, nestedTags: List<Tag>): MutableText {
        val result = Text.literal("")

        // First, process the text to remove tags but track their positions
        data class TagPosition(val start: Int, val end: Int, val tag: Tag)
        val tagPositions = mutableListOf<TagPosition>()
        val cleanText = buildString {
            var skipUntil = 0
            text.forEachIndexed { index, char ->
                if (index < skipUntil) return@forEachIndexed

                val tag = nestedTags.find { tag ->
                    index == text.indexOf("<${tag.name}") ||
                            index == text.indexOf("</${tag.name}>")
                }

                if (tag != null) {
                    if (index == text.indexOf("<${tag.name}")) {
                        val contentStart = text.indexOf('>', index) + 1
                        val contentEnd = text.indexOf("</${tag.name}>", contentStart)
                        tagPositions.add(TagPosition(length, length + contentEnd - contentStart, tag))
                        skipUntil = contentStart
                    } else {
                        skipUntil = index + tag.name.length + 3 // Skip </tag>
                    }
                } else if (index >= skipUntil) {
                    append(char)
                }
            }
        }

        // Now apply formatting to the clean text
        cleanText.forEachIndexed { index, char ->
            // Get all tags that affect this position
            val activeTags = tagPositions.filter { pos ->
                index >= pos.start && index < pos.end
            }

            // Build combined style from all active tags
            var combinedStyle = Style.EMPTY
            for (tagPos in activeTags) {
                combinedStyle = when (tagPos.tag.name) {
                    "bold" -> combinedStyle.withBold(true)
                    "italic" -> combinedStyle.withItalic(true)
                    "underline" -> combinedStyle.withUnderline(true)
                    // Add other formatting cases as needed
                    else -> combinedStyle
                }
            }

            // Apply gradient color to the combined style
            val ratio = index.toFloat() / (cleanText.length - 1).coerceAtLeast(1)
            val color = interpolateColors(startColor, endColor, ratio)
            combinedStyle = combinedStyle.withColor(color)

            // Add the character with combined formatting
            result.append(Text.literal(char.toString()).setStyle(combinedStyle))
        }

        return result
    }

    private fun interpolateColors(startColor: Int, endColor: Int, ratio: Float): Int {
        val r = interpolate((startColor shr 16) and 0xFF, (endColor shr 16) and 0xFF, ratio)
        val g = interpolate((startColor shr 8) and 0xFF, (endColor shr 8) and 0xFF, ratio)
        val b = interpolate(startColor and 0xFF, endColor and 0xFF, ratio)
        return (r shl 16) or (g shl 8) or b
    }

    private fun interpolate(start: Int, end: Int, ratio: Float): Int =
        (start + (end - start) * ratio).roundToInt()

    private fun decodeColor(hex: String): Int = try {
        when {
            hex.startsWith("#") -> Integer.decode("0x${hex.substring(1)}")
            COLORS.containsKey(hex.lowercase()) -> COLORS[hex.lowercase()]!!
            else -> Integer.decode(hex)
        }
    } catch (e: NumberFormatException) {
        0xFFFFFF
    }
}