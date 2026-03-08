import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

//! Single page view for BUG-01 detail ViewLoop. Renders either waiting state,
//! or header (action, routes, hint, freshness) + summary chunk.
class DetailPageView extends WatchUi.View {

    private var _waiting as Boolean = false;
    private var _header as Dictionary? = null;
    private var _summaryChunk as String = "";

    function initialize(waiting as Boolean, header as Dictionary?, summaryChunk as String) {
        View.initialize();
        _waiting = waiting;
        _header = header;
        _summaryChunk = summaryChunk;
    }

    function onLayout(dc as Graphics.Dc) as Void {
    }

    function onShow() as Void {
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        View.onUpdate(dc);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var textW = 310;
        var pad = 14;
        var y = 52;

        if (_waiting) {
            dc.drawText(
                cx,
                dc.getHeight() / 2,
                Graphics.FONT_MEDIUM,
                "Waiting for update...",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
            );
            return;
        }

        if (_header != null) {
            var header = _header as Dictionary;
            var actionColor = header.get("actionColor") as Number;
            var actionText = header.get("actionText") as String;
            dc.setColor(actionColor, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, y, Graphics.FONT_LARGE, actionText, Graphics.TEXT_JUSTIFY_CENTER);
            y += dc.getFontHeight(Graphics.FONT_LARGE) + pad;

            var routesStr = header.get("routesStr") as String?;
            if (routesStr != null && routesStr.length() > 0) {
                var routes = MtaColors.splitCsv(routesStr);
                var n = routes.size();
                if (n > 0) {
                    var badgeRadius = 13;
                    var badgeGap = 4;
                    var badgeDiameter = badgeRadius * 2;
                    var totalW = n * badgeDiameter + (n - 1) * badgeGap;
                    var bx = cx - totalW / 2 + badgeRadius;
                    var by = y + badgeRadius;
                    for (var i = 0; i < n; i++) {
                        var line = routes[i] as String;
                        var lineColor = MtaColors.getLineColor(line);
                        dc.setColor(lineColor, Graphics.COLOR_TRANSPARENT);
                        dc.fillCircle(bx, by, badgeRadius);
                        var textColor = MtaColors.isLightBackground(line)
                            ? Graphics.COLOR_BLACK
                            : Graphics.COLOR_WHITE;
                        dc.setColor(textColor, Graphics.COLOR_TRANSPARENT);
                        dc.drawText(bx, by, Graphics.FONT_TINY, line,
                            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
                        bx += badgeDiameter + badgeGap;
                    }
                    y += badgeDiameter + pad;
                }
            }

            var rerouteHint = header.get("rerouteHint") as String?;
            if (rerouteHint != null && rerouteHint.length() > 0) {
                var hintH = 80;
                var fitted = Graphics.fitTextToArea(rerouteHint, Graphics.FONT_TINY, textW, hintH, true);
                if (fitted != null) {
                    var hintArea = new WatchUi.TextArea({
                        :text => fitted,
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
            }

            var freshnessText = header.get("freshnessText") as String?;
            if (freshnessText != null && freshnessText.length() > 0) {
                dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
                dc.drawText(cx, y, Graphics.FONT_TINY, freshnessText, Graphics.TEXT_JUSTIFY_CENTER);
                y += dc.getFontHeight(Graphics.FONT_TINY) + pad;
            }
        }

        if (_summaryChunk.length() > 0) {
            var fitted = Graphics.fitTextToArea(_summaryChunk, Graphics.FONT_SMALL, textW, dc.getHeight() - y - 30, false);
            var toDraw = (fitted != null) ? fitted : _summaryChunk;
            var summaryArea = new WatchUi.TextArea({
                :text => toDraw,
                :color => Graphics.COLOR_WHITE,
                :font => Graphics.FONT_SMALL,
                :locX => cx - (textW / 2),
                :locY => y,
                :width => textW,
                :height => dc.getHeight() - y - 30,
                :justification => Graphics.TEXT_JUSTIFY_CENTER
            });
            summaryArea.draw(dc);
        }
    }

    function onHide() as Void {
    }
}
