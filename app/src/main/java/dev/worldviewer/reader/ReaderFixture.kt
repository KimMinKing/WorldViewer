package dev.worldviewer.reader

object ReaderFixture {
    val text: String = buildString {
        appendLine("第一章　山雨欲来")
        appendLine()
        repeat(700) { index ->
            append("第${index + 1}段　大家好。欧阳锋抬头望向𠮷野家的旧匾，轻声说：\"江湖路远，先把原文读清楚。\" ")
            append("混合文字 English words, e\u0301, 👩‍💻，以及标点都必须保持原来的 UTF-16 位置。")
            if (index % 7 == 0) {
                append("这是用于观察很长单一段落换行与回收行为的连续文本，不代表真实小说内容。".repeat(8))
            }
            appendLine()
            appendLine()
        }
    }
}
