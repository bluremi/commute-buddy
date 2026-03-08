import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

(:glance)
class CommuteBuddyGlanceView extends WatchUi.GlanceView {

    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var action = Application.Storage.getValue("cs_action");
        var affectedRoutes = Application.Storage.getValue("cs_affected_routes");

        if (!(action instanceof String)) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(
                dc.getWidth() / 2,
                dc.getHeight() / 2,
                Graphics.FONT_GLANCE,
                "Waiting...",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
            );
            return;
        }

        var actionStr = action as String;
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        var font = Graphics.FONT_GLANCE;

        if (actionStr.equals("NORMAL")) {
            dc.setColor(Graphics.COLOR_GREEN, Graphics.COLOR_BLACK);
            dc.drawText(cx, cy, font, "Normal",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else if (actionStr.equals("STAY_HOME")) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(cx, cy, font, "Stay Home",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            // MINOR_DELAYS or REROUTE: prefix in action color + route letters in MTA colors
            var prefix = actionStr.equals("MINOR_DELAYS") ? "Delays \u2014 " : "Reroute \u2014 ";
            var prefixColor = actionStr.equals("MINOR_DELAYS")
                ? Graphics.COLOR_YELLOW
                : Graphics.COLOR_RED;

            var routes = (affectedRoutes instanceof String) ? (affectedRoutes as String) : "";
            var routesList = MtaColors.splitCsv(routes);
            var n = routesList.size();

            // Measure total width of all segments to compute centered start x
            var totalW = (dc.getTextDimensions(prefix, font) as Array<Number>)[0] as Number;
            for (var i = 0; i < n; i++) {
                if (i > 0) {
                    totalW += (dc.getTextDimensions(",", font) as Array<Number>)[0] as Number;
                }
                totalW += (dc.getTextDimensions(routesList[i] as String, font) as Array<Number>)[0] as Number;
            }

            // Draw each segment left-to-right from the centered origin
            var x = cx - totalW / 2;

            dc.setColor(prefixColor, Graphics.COLOR_BLACK);
            var prefixW = (dc.getTextDimensions(prefix, font) as Array<Number>)[0] as Number;
            dc.drawText(x, cy, font, prefix,
                Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
            x += prefixW;

            for (var i = 0; i < n; i++) {
                if (i > 0) {
                    dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
                    var commaW = (dc.getTextDimensions(",", font) as Array<Number>)[0] as Number;
                    dc.drawText(x, cy, font, ",",
                        Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
                    x += commaW;
                }
                var line = routesList[i] as String;
                dc.setColor(MtaColors.getLineColor(line), Graphics.COLOR_BLACK);
                var lineW = (dc.getTextDimensions(line, font) as Array<Number>)[0] as Number;
                dc.drawText(x, cy, font, line,
                    Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
                x += lineW;
            }
        }
    }

}
