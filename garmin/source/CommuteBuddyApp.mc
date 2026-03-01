import Toybox.Application;
import Toybox.WatchUi;

class CommuteBuddyApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
    }

    function onStop(state) {
    }

    function getInitialView() {
        return [new CommuteBuddyView()];
    }

    function getGlanceView() {
        return [new CommuteBuddyGlanceView()];
    }

}
