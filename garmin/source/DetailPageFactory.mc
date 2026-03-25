import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

using DetailPagination;

//! ViewLoopFactory for BUG-01 detail pages. Builds page model from storage,
//! chunks summary via DetailPagination, and provides views to ViewLoop.
class DetailPageFactory extends WatchUi.ViewLoopFactory {

    private var _pages as Array<Dictionary> = [];

    function initialize() {
        ViewLoopFactory.initialize();
        _pages = buildPageModel();
    }

    function getSize() as Number {
        return _pages.size();
    }

    function getView(pageIndex as Number) {
        var page = _pages[pageIndex];
        if (page.get("diagnostics") == true) {
            return [new DiagnosticsPageView(), new DetailPageDelegate()];
        }
        var waiting = page.get("waiting") as Boolean;
        var header = page.get("header") as Dictionary?;
        var summaryChunk = page.get("summaryChunk") as String;
        var view = new DetailPageView(waiting, header, summaryChunk);
        var delegate = new DetailPageDelegate();
        return [view, delegate];
    }

    private function buildPageModel() as Array<Dictionary> {
        var pages = [] as Array<Dictionary>;
        var action = Application.Storage.getValue("cs_action");

        if (!(action instanceof String)) {
            pages.add({
                "waiting" => true,
                "header" => null,
                "summaryChunk" => ""
            });
            pages.add({"diagnostics" => true});
            return pages;
        }

        var actionStr = action as String;
        var summary = Application.Storage.getValue("cs_summary");
        var affectedRoutes = Application.Storage.getValue("cs_affected_routes");
        var rerouteHint = Application.Storage.getValue("cs_reroute_hint");
        var timestamp = Application.Storage.getValue("cs_timestamp");

        var actionColor;
        var actionText;
        if (actionStr.equals("NORMAL")) {
            actionColor = Graphics.COLOR_GREEN;
            actionText = "Normal";
        } else if (actionStr.equals("MINOR_DELAYS")) {
            actionColor = Graphics.COLOR_YELLOW;
            actionText = "Minor Delays";
        } else if (actionStr.equals("REROUTE")) {
            actionColor = Graphics.COLOR_RED;
            actionText = "Reroute";
        } else {
            actionColor = Graphics.COLOR_LT_GRAY;
            actionText = "Stay Home";
        }

        var routesStr = "";
        if (affectedRoutes instanceof String) {
            var r = affectedRoutes as String;
            if (!actionStr.equals("NORMAL") || !r.equals("")) {
                routesStr = r;
            }
        }

        var hintStr = "";
        if (actionStr.equals("REROUTE") && rerouteHint instanceof String) {
            hintStr = rerouteHint as String;
        }

        var freshnessText = "";
        if (timestamp instanceof Number) {
            var ageSecs = Time.now().value() - (timestamp as Number).toLong();
            if (ageSecs < 60l) {
                freshnessText = "<1 min ago";
            } else if (ageSecs < 3600l) {
                freshnessText = (ageSecs / 60l).toString() + " min ago";
            } else if (ageSecs < 7200l) {
                freshnessText = (ageSecs / 3600l).toString() + " hr ago";
            } else {
                freshnessText = "Stale";
            }
        }

        var textW = 310;
        var measuredHintHeight = 0;
        if (hintStr.length() > 0) {
            measuredHintHeight = measureHintHeight(hintStr, Graphics.FONT_TINY, textW);
        }

        var headerDict = {
            "actionColor" => actionColor,
            "actionText" => actionText,
            "routesStr" => routesStr,
            "rerouteHint" => hintStr,
            "freshnessText" => freshnessText,
            "hintHeight" => measuredHintHeight
        };

        var summaryStr = (summary instanceof String) ? (summary as String) : "";
        var screenH = System.getDeviceSettings().screenHeight;
        var headerHeight = Graphics.getFontHeight(Graphics.FONT_LARGE) + 14;
        if (routesStr.length() > 0) {
            headerHeight += 40 + 14; // badge diameter (2 * radius 20) + pad
        }
        if (freshnessText.length() > 0) {
            headerHeight += Graphics.getFontHeight(Graphics.FONT_XTINY) + 14;
        }
        if (measuredHintHeight > 0) {
            headerHeight += measuredHintHeight + 14;
        }
        // Page 1: summary fits below header. Pages 2+: no header, full page for summary.
        var bodyHeightPage1 = screenH - 52 - headerHeight - 30;
        var bodyHeightPage2Plus = screenH - 52 - 30;
        var minLineH = Graphics.getFontHeight(Graphics.FONT_XTINY);

        if (bodyHeightPage1 >= minLineH) {
            // Page 1 has room for at least one line of summary below the header.
            var firstChunks = DetailPagination.chunkSummary(summaryStr, Graphics.FONT_XTINY, textW, bodyHeightPage1);
            if (firstChunks.size() == 0) {
                pages.add({"waiting" => false, "header" => headerDict, "summaryChunk" => ""});
            } else {
                pages.add({"waiting" => false, "header" => headerDict, "summaryChunk" => firstChunks[0]});
                var remainder = DetailPagination.getRemainderAfterChunk(summaryStr, firstChunks[0]);
                var restChunks = DetailPagination.chunkSummary(remainder, Graphics.FONT_XTINY, textW, bodyHeightPage2Plus);
                for (var i = 0; i < restChunks.size(); i++) {
                    pages.add({"waiting" => false, "header" => null, "summaryChunk" => restChunks[i]});
                }
            }
        } else {
            // Header fills page 1 entirely — put all summary on subsequent pages.
            pages.add({"waiting" => false, "header" => headerDict, "summaryChunk" => ""});
            var restChunks = DetailPagination.chunkSummary(summaryStr, Graphics.FONT_XTINY, textW, bodyHeightPage2Plus);
            for (var i = 0; i < restChunks.size(); i++) {
                pages.add({"waiting" => false, "header" => null, "summaryChunk" => restChunks[i]});
            }
        }

        pages.add({"diagnostics" => true});
        return pages;
    }

    //! Measure the pixel height needed to render text at the given font and width.
    //! fitTextToArea inserts \n chars for line breaks, so fitted.length() >= original
    //! means full text fits (original chars + injected newlines). Truncated text is
    //! shorter (words dropped + "..." appended).
    private function measureHintHeight(text as String, font as Graphics.FontType, maxW as Number) as Number {
        var fontH = Graphics.getFontHeight(font);
        for (var lines = 1; lines <= 6; lines++) {
            var h = lines * fontH;
            var fitted = Graphics.fitTextToArea(text, font, maxW, h, true);
            if (fitted != null && (fitted as String).length() >= text.length()) {
                return h;
            }
        }
        return 6 * fontH;
    }
}
