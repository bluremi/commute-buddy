import Toybox.Lang;
import Toybox.WatchUi;

//! Per-page delegate for detail pages. The ViewLoop uses the vertical axis
//! (SWIPE_UP/SWIPE_DOWN) for paging, so the horizontal axis is free: a left or
//! right swipe reveals the ad-hoc "Fetch update" screen as a pushed overlay
//! (FEAT-16). Both horizontal directions are accepted because the system can
//! consume SWIPE_RIGHT as a back/exit gesture before this delegate sees it.
class DetailPageDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    function onSwipe(evt as WatchUi.SwipeEvent) as Boolean {
        var dir = evt.getDirection();
        if (dir == WatchUi.SWIPE_LEFT || dir == WatchUi.SWIPE_RIGHT) {
            WatchUi.pushView(new AdHocPageView(), new AdHocPageDelegate(), WatchUi.SLIDE_LEFT);
            return true;
        }
        return false;
    }
}
