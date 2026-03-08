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

        var headerDict = {
            "actionColor" => actionColor,
            "actionText" => actionText,
            "routesStr" => routesStr,
            "rerouteHint" => hintStr,
            "freshnessText" => freshnessText
        };

        var summaryStr = (summary instanceof String) ? (summary as String) : "";
        var screenH = System.getDeviceSettings().screenHeight;
        var headerHeight = Graphics.getFontHeight(Graphics.FONT_LARGE) + 14;
        if (routesStr.length() > 0) {
            headerHeight += 32 + 14; // badge diameter (2 * radius 16) + pad
        }
        if (hintStr.length() > 0) {
            headerHeight += 80 + 14;
        }
        if (freshnessText.length() > 0) {
            headerHeight += Graphics.getFontHeight(Graphics.FONT_TINY) + 14;
        }
        // Page 1: summary fits below header. Pages 2+: no header, full page for summary.
        var bodyHeightPage1 = screenH - 52 - headerHeight - 30;
        var bodyHeightPage2Plus = screenH - 52 - 30;
        if (bodyHeightPage1 < 80) {
            bodyHeightPage1 = 200;
        }
        if (bodyHeightPage2Plus < 80) {
            bodyHeightPage2Plus = 200;
        }

        var textW = 310;
        var chunks = [] as Array<String>;
        var firstChunks = DetailPagination.chunkSummary(summaryStr, Graphics.FONT_SMALL, textW, bodyHeightPage1);
        if (firstChunks.size() > 0) {
            chunks.add(firstChunks[0]);
            var remainder = DetailPagination.getRemainderAfterChunk(summaryStr, firstChunks[0]);
            var restChunks = DetailPagination.chunkSummary(remainder, Graphics.FONT_SMALL, textW, bodyHeightPage2Plus);
            for (var i = 0; i < restChunks.size(); i++) {
                chunks.add(restChunks[i]);
            }
        }

        if (chunks.size() == 0) {
            pages.add({
                "waiting" => false,
                "header" => headerDict,
                "summaryChunk" => ""
            });
        } else {
            pages.add({
                "waiting" => false,
                "header" => headerDict,
                "summaryChunk" => chunks[0]
            });
            for (var i = 1; i < chunks.size(); i++) {
                pages.add({
                    "waiting" => false,
                    "header" => null,
                    "summaryChunk" => chunks[i]
                });
            }
        }

        return pages;
    }
}
