import Toybox.Lang;
import Toybox.WatchUi;

class CommuteBuddyDelegate extends WatchUi.BehaviorDelegate {

    private var _view as CommuteBuddyView;

    function initialize(view as CommuteBuddyView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSwipe(swipeEvent as WatchUi.SwipeEvent) as Boolean {
        var dir = swipeEvent.getDirection();
        if (dir == WatchUi.SWIPE_UP) {
            _view.scrollBy(90);
            return true;
        } else if (dir == WatchUi.SWIPE_DOWN) {
            _view.scrollBy(-90);
            return true;
        }
        return false;
    }

}
