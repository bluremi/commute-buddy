import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

class CommuteBuddyApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state) {
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
    }

    function onStop(state) {
    }

    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data == null || !(data instanceof Dictionary)) {
            return;
        }
        var dict = data as Dictionary;

        var status = dict.get("status");
        var routeStr = dict.get("route_string");
        var reason = dict.get("reason");
        var timestamp = dict.get("timestamp");

        // Validate required fields before storing — invalid/missing fields are silently ignored
        if (!(status instanceof Number)) {
            return;
        }
        var statusNum = status as Number;
        if (statusNum < 0 || statusNum > 2) {
            return;
        }
        if (!(routeStr instanceof String)) {
            return;
        }
        if (!(reason instanceof String)) {
            return;
        }

        Application.Storage.setValue("cs_status", statusNum);
        Application.Storage.setValue("cs_route", routeStr as String);
        Application.Storage.setValue("cs_reason", reason as String);
        if (timestamp instanceof Number) {
            Application.Storage.setValue("cs_timestamp", timestamp as Number);
        }
        WatchUi.requestUpdate();
    }

    function getInitialView() {
        return [new CommuteBuddyView()];
    }

    function getGlanceView() {
        return [new CommuteBuddyGlanceView()];
    }

}
