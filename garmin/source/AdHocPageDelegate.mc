import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//! Delegate for the ad-hoc "Fetch update" page (FEAT-16, index 0 of the detail
//! ViewLoop). On tap, hit-tests which direction button was pressed (top half =
//! To Work, bottom half = To Home — matching AdHocPageView's layout), transmits
//! a POLL_NOW command to the paired phone, then returns to the status view
//! (index 1) by switching to a fresh ViewLoop.
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

    //! Rebuild a fresh detail ViewLoop landing on the status page (index 1) and
    //! slide back to it. ViewLoop has no page-jump method, so a rebuild + switch
    //! is the only way to auto-return. A tap is a valid foreground input context
    //! for switchToView.
    hidden function returnToStatus() as Void {
        var factory = new DetailPageFactory();
        var loop = new WatchUi.ViewLoop(factory, {:page => 1, :wrap => false});
        var delegate = new WatchUi.ViewLoopDelegate(loop);
        WatchUi.switchToView(loop, delegate, WatchUi.SLIDE_RIGHT);
    }
}
