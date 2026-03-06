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

        var action = dict.get("action");
        var summary = dict.get("summary");
        var affectedRoutes = dict.get("affected_routes");
        var rerouteHint = dict.get("reroute_hint");
        var timestamp = dict.get("timestamp");

        // Validate required fields — action must be one of NORMAL/MINOR_DELAYS/REROUTE/STAY_HOME
        if (!(action instanceof String)) {
            return;
        }
        var actionStr = action as String;
        if (!actionStr.equals("NORMAL") && !actionStr.equals("MINOR_DELAYS") && !actionStr.equals("REROUTE") && !actionStr.equals("STAY_HOME")) {
            return;
        }
        if (!(summary instanceof String)) {
            return;
        }
        if (!(affectedRoutes instanceof String)) {
            return;
        }

        Application.Storage.setValue("cs_action", actionStr);
        Application.Storage.setValue("cs_summary", summary as String);
        Application.Storage.setValue("cs_affected_routes", affectedRoutes as String);
        if (rerouteHint instanceof String) {
            Application.Storage.setValue("cs_reroute_hint", rerouteHint as String);
        } else {
            Application.Storage.deleteValue("cs_reroute_hint");
        }
        if (timestamp instanceof Number) {
            Application.Storage.setValue("cs_timestamp", timestamp as Number);
        }
        WatchUi.requestUpdate();
    }

    function getInitialView() {
        var factory = new DetailPageFactory();
        var viewLoop = new WatchUi.ViewLoop(factory, {:wrap => false});
        var delegate = new WatchUi.ViewLoopDelegate(viewLoop);
        return [viewLoop, delegate];
    }

    function getGlanceView() {
        return [new CommuteBuddyGlanceView()];
    }

}
