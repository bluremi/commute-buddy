import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

class CommuteBuddyApp extends Application.AppBase {

    hidden var _listenerRegistered as Boolean = false;
    hidden var _regTimer as Timer.Timer?;

    // True only in the foreground full-app process (set at the end of
    // getInitialView, cleared in onStop). Never set in the glance process, which
    // is a separate process where getInitialView is never called. Gates the
    // live-refresh rebuild in onPhoneMessage: switchToView throws
    // OperationNotAllowedException from a glance/background context (FEAT-16).
    hidden var _fullAppForeground as Boolean = false;

    function initialize() {
        AppBase.initialize();
    }

    (:glance)
    function onStart(state) {
        // Registration moved to getGlanceView()/getInitialView() — calling
        // registerForPhoneAppMessages() this early in the lifecycle crashes
        // intermittently after OS hard-kills (native "Failed invoking <symbol>").
    }

    (:glance)
    function onStop(state) {
        Communications.registerForPhoneAppMessages(null);
        _listenerRegistered = false;
        _fullAppForeground = false;
    }

    (:glance)
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

        // Glance process (or full app stopped): a plain redraw is enough — the
        // glance view reads storage on every render.
        if (!_fullAppForeground) {
            WatchUi.requestUpdate();
            return;
        }

        // Foreground full app: DetailPageView renders constructor snapshots and
        // ViewLoop has no reload method, so the only way to show fresh data is to
        // build a new loop and switch to it. Gated to the foreground process
        // (switchToView throws OperationNotAllowedException from background) and
        // wrapped in try/catch as a final safety net.
        try {
            var factory = new DetailPageFactory();
            var loop = new WatchUi.ViewLoop(factory, {:wrap => false});
            var delegate = new WatchUi.ViewLoopDelegate(loop);
            WatchUi.switchToView(loop, delegate, WatchUi.SLIDE_IMMEDIATE);
        } catch (e instanceof Lang.Exception) {
            WatchUi.requestUpdate();
        }
    }

    function getInitialView() {
        registerPhoneListener();
        var factory = new DetailPageFactory();
        var viewLoop = new WatchUi.ViewLoop(factory, {:wrap => false});
        var delegate = new WatchUi.ViewLoopDelegate(viewLoop);
        _fullAppForeground = true;
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
            if (cb == null) {
                return;
            }
            Communications.registerForPhoneAppMessages(cb);
            _listenerRegistered = true;
        } catch (e instanceof Lang.Exception) {
            // Registration can throw after OS hard-kills; swallow to avoid crashing.
        }
    }

}
