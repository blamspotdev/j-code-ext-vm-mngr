package dev.blamspot.jcode.ext.vm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * A very small VT emulator, enough for a serial console.
 *
 * The line QEMU exposes is not a stream of text: it carries SGR colours, and it redraws with `\r`,
 * erase-line and cursor moves — a progress bar or a boot spinner is the same line written over and
 * over. Appending it verbatim would show every frame of that animation, so the bytes are replayed
 * into a cell grid instead, and what comes out is what a terminal would be showing now.
 *
 * The buffer is read as base64 precisely so this can happen: the host's line-based output handling
 * would otherwise strip the ESC and `\r` bytes this depends on.
 */
internal object Ansi {

    /** The 16 ANSI colours, in the palette JCode's own terminal uses. */
    private val PALETTE = listOf(
        Color(0xFF1E1E1E), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5E510),
        Color(0xFF2472C8), Color(0xFFBC3FBC), Color(0xFF11A8CD), Color(0xFFCCCCCC),
        Color(0xFF666666), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543),
        Color(0xFF3B8EEA), Color(0xFFD670D6), Color(0xFF29B8DB), Color(0xFFFFFFFF),
    )

    private data class Cell(
        val ch: Char = ' ',
        val fg: Int = -1,
        val bg: Int = -1,
        val bold: Boolean = false,
        val underline: Boolean = false,
    )

    private val BLANK = Cell()

    /** Replays [raw] and returns what the screen holds, one [AnnotatedString] per line. */
    fun render(raw: String, defaultColor: Color): List<AnnotatedString> = grid(raw).map { line ->
        buildAnnotatedString {
            var run = StringBuilder()
            var style: SpanStyle? = null
            var started = false

            fun flush() {
                if (run.isEmpty()) return
                val s = style
                if (s == null) append(run.toString()) else withStyle(s) { append(run.toString()) }
                run = StringBuilder()
            }

            for (c in line) {
                val s = styleOf(c, defaultColor)
                if (!started || s != style) {
                    flush()
                    style = s
                    started = true
                }
                run.append(c.ch)
            }
            flush()
        }
    }

    private fun styleOf(c: Cell, defaultColor: Color): SpanStyle? {
        if (c.fg < 0 && c.bg < 0 && !c.bold && !c.underline) return null
        return SpanStyle(
            color = if (c.fg in PALETTE.indices) PALETTE[c.fg] else defaultColor,
            background = if (c.bg in PALETTE.indices) PALETTE[c.bg] else Color.Unspecified,
            fontWeight = if (c.bold) FontWeight.Bold else null,
            textDecoration = if (c.underline) TextDecoration.Underline else null,
        )
    }

    private fun grid(raw: String): List<List<Cell>> {
        val lines = mutableListOf(mutableListOf<Cell>())
        var row = 0
        var col = 0
        var fg = -1
        var bg = -1
        var bold = false
        var underline = false
        var inverse = false

        fun line(): MutableList<Cell> {
            while (lines.size <= row) lines.add(mutableListOf())
            return lines[row]
        }

        fun put(ch: Char) {
            val ln = line()
            while (ln.size < col) ln.add(BLANK)
            var f = fg
            var b = bg
            if (bold && f in 0..7) f += 8
            if (inverse) {
                val t = f
                f = if (b < 0) 0 else b
                b = if (t < 0) 7 else t
            }
            val cell = Cell(ch, f, b, bold, underline)
            if (col < ln.size) ln[col] = cell else ln.add(cell)
            col++
        }

        fun sgr(codes: List<Int>) {
            val ns = codes.ifEmpty { listOf(0) }
            var k = 0
            while (k < ns.size) {
                when (val n = ns[k]) {
                    0 -> { fg = -1; bg = -1; bold = false; underline = false; inverse = false }
                    1 -> bold = true
                    22 -> bold = false
                    4 -> underline = true
                    24 -> underline = false
                    7 -> inverse = true
                    27 -> inverse = false
                    39 -> fg = -1
                    49 -> bg = -1
                    in 30..37 -> fg = n - 30
                    in 90..97 -> fg = n - 90 + 8
                    in 40..47 -> bg = n - 40
                    in 100..107 -> bg = n - 100 + 8
                    38, 48 -> when (ns.getOrNull(k + 1)) {
                        // 256-colour: only the first 16 map onto this palette; the rest fall back.
                        5 -> {
                            val c = ns.getOrNull(k + 2) ?: -1
                            val v = if (c in 0..15) c else -1
                            if (n == 38) fg = v else bg = v
                            k += 2
                        }
                        // 24-bit: skipped rather than approximated.
                        2 -> k += 4
                    }
                }
                k++
            }
        }

        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == '\u001B' -> {
                    when (raw.getOrNull(i + 1)) {
                        '[' -> {
                            var j = i + 2
                            val params = StringBuilder()
                            while (j < raw.length && (raw[j].isDigit() || raw[j] == ';' || raw[j] == '?')) {
                                params.append(raw[j])
                                j++
                            }
                            val cmd = raw.getOrNull(j) ?: ' '
                            val nums = params.toString().replace("?", "").split(';')
                                .map { it.toIntOrNull() ?: 0 }
                            val p0 = nums.firstOrNull() ?: 0
                            when (cmd) {
                                'm' -> sgr(if (params.isEmpty()) listOf(0) else nums)
                                'A' -> row = maxOf(0, row - (if (p0 == 0) 1 else p0))
                                'B' -> { row += if (p0 == 0) 1 else p0; line() }
                                'C' -> col += if (p0 == 0) 1 else p0
                                'D' -> col = maxOf(0, col - (if (p0 == 0) 1 else p0))
                                'G' -> col = maxOf(0, (if (p0 == 0) 1 else p0) - 1)
                                'd' -> { row = maxOf(0, (if (p0 == 0) 1 else p0) - 1); line() }
                                'H', 'f' -> {
                                    row = maxOf(0, (nums.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1)
                                    col = maxOf(0, (nums.getOrNull(1)?.takeIf { it > 0 } ?: 1) - 1)
                                    line()
                                }
                                'J' -> when (p0) {
                                    2, 3 -> { lines.clear(); lines.add(mutableListOf()); row = 0; col = 0 }
                                    0 -> {
                                        val ln = line()
                                        while (ln.size > col) ln.removeAt(ln.size - 1)
                                        while (lines.size > row + 1) lines.removeAt(lines.size - 1)
                                    }
                                }
                                'K' -> {
                                    val ln = line()
                                    when (p0) {
                                        0 -> while (ln.size > col) ln.removeAt(ln.size - 1)
                                        1 -> for (x in 0 until minOf(col, ln.size)) ln[x] = BLANK
                                        2 -> ln.clear()
                                    }
                                }
                            }
                            i = j + 1
                        }
                        // OSC: swallowed whole, terminated by BEL or ST.
                        ']' -> {
                            var j = i + 2
                            while (j < raw.length && raw[j] != '\u0007' &&
                                !(raw[j] == '\u001B' && raw.getOrNull(j + 1) == '\\')
                            ) j++
                            i = if (raw.getOrNull(j) == '\u001B') j + 2 else j + 1
                        }
                        else -> i += 2
                    }
                }
                ch == '\n' -> { row++; col = 0; line(); i++ }
                ch == '\r' -> { col = 0; i++ }
                ch == '\b' -> { col = maxOf(0, col - 1); i++ }
                ch == '\t' -> { col = (col / 8 + 1) * 8; i++ }
                ch.code < 32 -> i++
                else -> { put(ch); i++ }
            }
        }
        return lines
    }
}
