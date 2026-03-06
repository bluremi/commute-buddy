import Toybox.Graphics;
import Toybox.Lang;

//! Pagination helper for BUG-01 detail view. Splits long summary text into
//! page-sized chunks using Graphics.fitTextToArea. Deterministic, avoids
//! mid-word breaks where practical, and handles empty/short/very-long text.
module DetailPagination {

    //! Chunk summary text into page-sized pieces that fit within the given area.
    //! @param text Summary text to paginate (may be null or empty)
    //! @param font Font to use for measurement (e.g. Graphics.FONT_SMALL)
    //! @param width Page body width in pixels
    //! @param height Page body height in pixels
    //! @return Array of non-empty strings, one per page. Empty input returns [].
    function chunkSummary(
        text as String?,
        font as Graphics.FontType,
        width as Number,
        height as Number
    ) as Array<String> {
        if (text == null || text.length() == 0) {
            return [] as Array<String>;
        }
        var chunks = [] as Array<String>;
        var remainder = text as String;

        while (remainder.length() > 0) {
            remainder = trimStart(remainder);
            if (remainder.length() == 0) {
                break;
            }

            // Fast path: entire remainder fits
            var fitted = Graphics.fitTextToArea(remainder, font, width, height, false);
            if (fitted != null) {
                chunks.add(fitted);
                break;
            }

            // Word-by-word: find largest prefix that fits (avoids mid-word breaks)
            var chunk = findLargestFittingPrefix(remainder, font, width, height);
            if (chunk.length() > 0) {
                chunks.add(chunk);
                remainder = remainder.substring(chunk.length(), null);
            } else {
                // Single token (word or run) too long for one page — must break
                var forced = forceChunk(remainder, font, width, height);
                chunks.add(forced);
                remainder = remainder.substring(forced.length(), null);
            }
        }

        return chunks;
    }

    // Find the largest prefix that fits, breaking at word boundaries.
    function findLargestFittingPrefix(
        text as String,
        font as Graphics.FontType,
        width as Number,
        height as Number
    ) as String {
        var lastFit = "";
        var pos = 0;

        while (pos < text.length()) {
            pos = skipSpaces(text, pos);
            if (pos >= text.length()) {
                break;
            }
            var wordEnd = nextWordEnd(text, pos);
            var word = text.substring(pos, wordEnd);
            var candidate = lastFit.length() == 0 ? word : lastFit + " " + word;

            if (Graphics.fitTextToArea(candidate, font, width, height, false) != null) {
                lastFit = candidate;
                pos = wordEnd;
            } else {
                break;
            }
        }

        return lastFit;
    }

    // Advance past whitespace; return index of first non-space or text.length().
    function skipSpaces(text as String, start as Number) as Number {
        var i = start;
        while (i < text.length()) {
            var c = text.substring(i, i + 1);
            if (!c.equals(" ") && !c.equals("\n") && !c.equals("\r") && !c.equals("\t")) {
                break;
            }
            i++;
        }
        return i;
    }

    // Index past the current word (start must be at a non-space).
    function nextWordEnd(text as String, start as Number) as Number {
        var i = start;
        while (i < text.length()) {
            var c = text.substring(i, i + 1);
            if (c.equals(" ") || c.equals("\n") || c.equals("\r") || c.equals("\t")) {
                break;
            }
            i++;
        }
        return i;
    }

    // Trim leading whitespace from string.
    function trimStart(s as String) as String {
        var i = 0;
        while (i < s.length()) {
            var c = s.substring(i, i + 1);
            if (!c.equals(" ") && !c.equals("\n") && !c.equals("\r") && !c.equals("\t")) {
                break;
            }
            i++;
        }
        return s.substring(i, null);
    }

    // Force a chunk when a single word doesn't fit — binary search for split point.
    function forceChunk(
        text as String,
        font as Graphics.FontType,
        width as Number,
        height as Number
    ) as String {
        var len = text.length();
        if (len == 0) {
            return "";
        }
        var low = 1;
        var high = len;
        while (low < high) {
            var mid = (low + high + 1) / 2;
            var prefix = text.substring(0, mid);
            if (Graphics.fitTextToArea(prefix, font, width, height, false) != null) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        if (low >= 1 && Graphics.fitTextToArea(text.substring(0, low), font, width, height, false) != null) {
            return text.substring(0, low);
        }
        // Fallback: truncate to fit (avoids infinite loop)
        var fitted = Graphics.fitTextToArea(text, font, width, height, true);
        return (fitted != null && fitted.length() > 0) ? fitted : text.substring(0, 1);
    }
}
