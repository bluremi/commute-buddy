import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

//! Ad-hoc "Fetch update" page (FEAT-16). Sits at index 0 of the detail
//! ViewLoop so a right-swipe from the status view (index 1) reveals it.
//! Shows a title and two tappable direction buttons: "To Work" (top half)
//! and "To Home" (bottom half). Hit-testing is done in AdHocPageDelegate
//! using the screen vertical midpoint — the layout here must match that.
class AdHocPageView extends WatchUi.View {

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
        var h = dc.getHeight();
        var midY = h / 2;

        // Title near the top.
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, 40, Graphics.FONT_XTINY, "Fetch update", Graphics.TEXT_JUSTIFY_CENTER);

        // Divider marking the To Work / To Home hit-test boundary.
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawLine(cx - 120, midY, cx + 120, midY);

        // "To Work" — top half.
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, midY - (h / 4), Graphics.FONT_MEDIUM, "To Work",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // "To Home" — bottom half.
        dc.drawText(cx, midY + (h / 4), Graphics.FONT_MEDIUM, "To Home",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function onHide() as Void {
    }
}
