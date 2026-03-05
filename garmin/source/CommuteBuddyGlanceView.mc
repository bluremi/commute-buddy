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

        var status = Application.Storage.getValue("cs_status");
        var route = Application.Storage.getValue("cs_route");
        var text = "Waiting...";
        if (status instanceof Number) {
            var statusNum = status as Number;
            if (statusNum == 0) {
                text = "Normal";
            } else if (statusNum == 1) {
                text = "Delays \u2014 " + route;
            } else if (statusNum == 2) {
                text = "Disrupted \u2014 " + route;
            }
        }

        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_GLANCE,
            text,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }

}
