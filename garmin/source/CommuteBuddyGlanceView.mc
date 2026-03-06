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
        var text = "Waiting...";
        var fgColor = Graphics.COLOR_WHITE;

        if (action instanceof String) {
            var actionStr = action as String;
            var routes = (affectedRoutes instanceof String) ? (affectedRoutes as String) : "";
            if (actionStr.equals("NORMAL")) {
                text = "Normal";
                fgColor = Graphics.COLOR_GREEN;
            } else if (actionStr.equals("MINOR_DELAYS")) {
                text = "Delays \u2014 " + routes;
                fgColor = Graphics.COLOR_YELLOW;
            } else if (actionStr.equals("REROUTE")) {
                text = "Reroute \u2014 " + routes;
                fgColor = Graphics.COLOR_RED;
            } else if (actionStr.equals("STAY_HOME")) {
                text = "Stay Home";
                fgColor = Graphics.COLOR_LT_GRAY;
            }
        }

        dc.setColor(fgColor, Graphics.COLOR_BLACK);
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_GLANCE,
            text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }

}
