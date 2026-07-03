import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//! Delegate for the ad-hoc "Fetch update" page (FEAT-16), pushed as an overlay
//! when the user swipes horizontally from the status view. On tap, hit-tests
//! which direction button was pressed (top half = To Work, bottom half = To
//! Home — matching AdHocPageView's layout), transmits a POLL_NOW command to the
//! paired phone, then pops back to the status view.
class AdHocPageDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        var coords = evt.getCoordinates();
        var y = coords[1];
        var screenH = System.getDeviceSettings().screenHeight;
        var direction = (y < screenH / 2) ? "TO_WORK" : "TO_HOME";
        transmitPoll(direction);
        returnToStatus();
        return true;
    }

    hidden function transmitPoll(direction as String) as Void {
        Communications.transmit("POLL_NOW:" + direction, null, new PollRequestListener());
    }

    //! Pop the ad-hoc overlay to return to the status ViewLoop exactly where the
    //! user left it. A tap is a valid foreground input context for popView.
    hidden function returnToStatus() as Void {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}
