import Toybox.Lang;
import Toybox.WatchUi;

//! Minimal delegate for detail pages. ViewLoopDelegate handles navigation;
//! this exists only to satisfy ViewLoopFactory.getView() signature.
class DetailPageDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }
}
