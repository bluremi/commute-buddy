import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

class CommuteBuddyApp extends Application.AppBase {

    hidden var _listenerRegistered as Boolean = false;
    hidden var _regTimer as Timer.Timer?;

    function initialize() {
        AppBase.initialize();
    }

    (:glance)
    function onStart(state) {
        var starts = Application.Storage.getValue("diag_starts");
        Application.Storage.setValue("diag_starts", (starts instanceof Number ? starts + 1 : 1));
        var freeMem = System.getSystemStats().freeMemory;
        Application.Storage.setValue("diag_free_mem_start", freeMem);
        var minMem = Application.Storage.getValue("diag_min_free_mem_start");
        if (!(minMem instanceof Number) || freeMem < (minMem as Number)) {
            Application.Storage.setValue("diag_min_free_mem_start", freeMem);
        }
        Application.Storage.setValue("diag_last_start_ts", Time.now().value());

        // Registration moved to getGlanceView()/getInitialView() — calling
        // registerForPhoneAppMessages() this early in the lifecycle crashes
        // intermittently after OS hard-kills (native "Failed invoking <symbol>").
    }

    (:glance)
    function onStop(state) {
        var stops = Application.Storage.getValue("diag_stops");
        Application.Storage.setValue("diag_stops", (stops instanceof Number ? stops + 1 : 1));
        Application.Storage.setValue("diag_free_mem_stop", System.getSystemStats().freeMemory);
        Application.Storage.setValue("diag_last_stop_ts", Time.now().value());
        Communications.registerForPhoneAppMessages(null);
        _listenerRegistered = false;
    }

    (:glance)
    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data == null || !(data instanceof Dictionary)) {
            return;
        }
        var dict = data as Dictionary;

        var msgs = Application.Storage.getValue("diag_msgs");
        Application.Storage.setValue("diag_msgs", (msgs instanceof Number ? msgs + 1 : 1));
        Application.Storage.setValue("diag_last_msg_ts", Time.now().value());
        Application.Storage.setValue("diag_last_msg_bytes", dict.toString().length());
        Application.Storage.setValue("diag_free_mem_msg", System.getSystemStats().freeMemory);

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
        registerPhoneListener();
        var factory = new DetailPageFactory();
        var viewLoop = new WatchUi.ViewLoop(factory, {:wrap => false});
        var delegate = new WatchUi.ViewLoopDelegate(viewLoop);
        return [viewLoop, delegate];
    }

    (:glance)
    function getGlanceView() {
        // Defer registration so the view renders before the risky native call.
        // If registerForPhoneAppMessages crashes, the glance shows "IQ!" instead
        // of a permanent blank tile — scroll away and back to self-heal.
        _regTimer = new Timer.Timer();
        _regTimer.start(method(:onRegTimer), 500, false);
        return [new CommuteBuddyGlanceView()];
    }

    (:glance)
    function onRegTimer() as Void {
        _regTimer = null;
        registerPhoneListener();
    }

    (:glance)
    hidden function registerPhoneListener() {
        if (_listenerRegistered) {
            return;
        }
        try {
            var cb = method(:onPhoneMessage);
            Application.Storage.setValue("diag_cb_resolved", cb != null ? 1 : 0);
            if (cb == null) {
                Application.Storage.setValue("diag_null_cb_at", Time.now().value());
                return;
            }
            Communications.registerForPhoneAppMessages(cb);
            _listenerRegistered = true;
            Application.Storage.setValue("diag_reg_ok", 1);
        } catch (e instanceof Lang.Exception) {
            Application.Storage.setValue("diag_err_phase", "reg_in_getView");
            Application.Storage.setValue("diag_err_msg", e.getErrorMessage());
        }
    }

}
