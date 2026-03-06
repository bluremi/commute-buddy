import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

class CommuteBuddyView extends WatchUi.View {

    function initialize() {
        View.initialize();
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

        // Action header — large font, color-coded
        dc.setColor(actionColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, 75, Graphics.FONT_LARGE, actionText, Graphics.TEXT_JUSTIFY_CENTER);

        // Summary — medium font, white, center-justified and wrapped
        if (summary instanceof String) {
            var summaryArea = new WatchUi.TextArea({
                :text => summary as String,
                :color => Graphics.COLOR_WHITE,
                :font => Graphics.FONT_MEDIUM,
                :locX => cx - 160,
                :locY => 135,
                :width => 320,
                :height => 80,
                :justification => Graphics.TEXT_JUSTIFY_CENTER
            });
            summaryArea.draw(dc);
        }

        // Affected routes — small font, white; omit for NORMAL with empty routes
        if (affectedRoutes instanceof String) {
            var routesStr = affectedRoutes as String;
            if (!actionStr.equals("NORMAL") || !routesStr.equals("")) {
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                dc.drawText(cx, 230, Graphics.FONT_SMALL, "Routes: " + routesStr, Graphics.TEXT_JUSTIFY_CENTER);
            }
        }

        // Reroute hint — small font, white; only when REROUTE and hint is stored
        if (actionStr.equals("REROUTE") && rerouteHint instanceof String) {
            var hintArea = new WatchUi.TextArea({
                :text => rerouteHint as String,
                :color => Graphics.COLOR_WHITE,
                :font => Graphics.FONT_SMALL,
                :locX => cx - 160,
                :locY => 265,
                :width => 320,
                :height => 50,
                :justification => Graphics.TEXT_JUSTIFY_CENTER
            });
            hintArea.draw(dc);
        }

        // Freshness — small font, light gray; relative age of the last update
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
            dc.drawText(cx, 330, Graphics.FONT_SMALL, freshnessText, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    function onHide() as Void {
    }

}
