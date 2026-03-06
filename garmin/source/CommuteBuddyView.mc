import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

class CommuteBuddyView extends WatchUi.View {

    private var _scrollOffset as Number = 0;

    function initialize() {
        View.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
    }

    function onShow() as Void {
    }

    function scrollBy(delta as Number) as Void {
        _scrollOffset += delta;
        if (_scrollOffset < 0) {
            _scrollOffset = 0;
        }
        WatchUi.requestUpdate();
    }

    // Counts pixel rows needed to render text at the given font/width by simulating
    // word-wrapping using dc.getTextWidthInPixels() and dc.getFontHeight().
    private function calcWrappedHeight(dc as Graphics.Dc, text as String, font as Graphics.FontType, width as Number) as Number {
        if (text.length() == 0) {
            return dc.getFontHeight(font);
        }
        var spaceW = dc.getTextWidthInPixels(" ", font);
        var lineH = dc.getFontHeight(font);
        var lineWidth = 0;
        var lines = 1;
        var wordStart = 0;
        for (var i = 0; i <= text.length(); i++) {
            var atEnd = (i == text.length());
            var isSpace = !atEnd && text.substring(i, i + 1).equals(" ");
            if (isSpace || atEnd) {
                if (i > wordStart) {
                    var word = text.substring(wordStart, i);
                    var wordW = dc.getTextWidthInPixels(word, font);
                    if (lineWidth == 0) {
                        lineWidth = wordW;
                    } else if (lineWidth + spaceW + wordW > width) {
                        lines++;
                        lineWidth = wordW;
                    } else {
                        lineWidth += spaceW + wordW;
                    }
                }
                wordStart = i + 1;
            }
        }
        return lines * lineH;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        View.onUpdate(dc);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var action = Application.Storage.getValue("cs_action");

        if (!(action instanceof String)) {
            dc.drawText(
                cx,
                dc.getHeight() / 2,
                Graphics.FONT_MEDIUM,
                "Waiting for update...",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
            );
            return;
        }

        var actionStr = action as String;
        var summary = Application.Storage.getValue("cs_summary");
        var affectedRoutes = Application.Storage.getValue("cs_affected_routes");
        var rerouteHint = Application.Storage.getValue("cs_reroute_hint");
        var timestamp = Application.Storage.getValue("cs_timestamp");

        // Map action tier to color and display text
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

        var textW = 310;
        var pad = 14;
        var y = 52 - _scrollOffset;

        // Action header — large font, color-coded
        dc.setColor(actionColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, y, Graphics.FONT_LARGE, actionText, Graphics.TEXT_JUSTIFY_CENTER);
        y += dc.getFontHeight(Graphics.FONT_LARGE) + pad;

        // Summary — small font, white, wrapped to exact content height
        if (summary instanceof String) {
            var summaryStr = summary as String;
            var summaryH = calcWrappedHeight(dc, summaryStr, Graphics.FONT_SMALL, textW);
            var summaryArea = new WatchUi.TextArea({
                :text => summaryStr,
                :color => Graphics.COLOR_WHITE,
                :font => Graphics.FONT_SMALL,
                :locX => cx - (textW / 2),
                :locY => y,
                :width => textW,
                :height => summaryH,
                :justification => Graphics.TEXT_JUSTIFY_CENTER
            });
            summaryArea.draw(dc);
            y += summaryH + pad;
        }

        // Affected routes — small font, white; omit for NORMAL with empty routes
        if (affectedRoutes instanceof String) {
            var routesStr = affectedRoutes as String;
            if (!actionStr.equals("NORMAL") || !routesStr.equals("")) {
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                dc.drawText(cx, y, Graphics.FONT_SMALL, "Routes: " + routesStr, Graphics.TEXT_JUSTIFY_CENTER);
                y += dc.getFontHeight(Graphics.FONT_SMALL) + pad;
            }
        }

        // Reroute hint — tiny font, white; only when REROUTE and hint is stored
        if (actionStr.equals("REROUTE") && rerouteHint instanceof String) {
            var hintStr = rerouteHint as String;
            var hintH = calcWrappedHeight(dc, hintStr, Graphics.FONT_TINY, textW);
            var hintArea = new WatchUi.TextArea({
                :text => hintStr,
                :color => Graphics.COLOR_WHITE,
                :font => Graphics.FONT_TINY,
                :locX => cx - (textW / 2),
                :locY => y,
                :width => textW,
                :height => hintH,
                :justification => Graphics.TEXT_JUSTIFY_CENTER
            });
            hintArea.draw(dc);
            y += hintH + pad;
        }

        // Freshness — tiny font, light gray
        if (timestamp instanceof Number) {
            var ageSecs = Time.now().value() - (timestamp as Number).toLong();
            var freshnessText;
            if (ageSecs < 60l) {
                freshnessText = "<1 min ago";
            } else if (ageSecs < 3600l) {
                freshnessText = (ageSecs / 60l).toString() + " min ago";
            } else if (ageSecs < 7200l) {
                freshnessText = (ageSecs / 3600l).toString() + " hr ago";
            } else {
                freshnessText = "Stale";
            }
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Graphics.FONT_TINY, freshnessText, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    function onHide() as Void {
    }

}
