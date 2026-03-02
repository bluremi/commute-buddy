import Toybox.Application;
import Toybox.Communications;
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
        if (data instanceof Number) {
            Application.Storage.setValue("code", data);
            WatchUi.requestUpdate();
        }
    }

    function getInitialView() {
        return [new CommuteBuddyView()];
    }

    function getGlanceView() {
        return [new CommuteBuddyGlanceView()];
    }

}
