import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.WatchUi;

//! Diagnostics page appended to detail ViewLoop (BUG-12). Reads all diag_* keys
//! from Application.Storage and renders them as a key-value dump. No glance
//! annotation — runs only in the full app context.
class DiagnosticsPageView extends WatchUi.View {

    function initialize() {
        View.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
    }

    function onShow() as Void {
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        View.onUpdate(dc);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var textW = 310;
        var now = Time.now().value() as Number;

        var starts = Application.Storage.getValue("diag_starts");
        var stops = Application.Storage.getValue("diag_stops");
        var msgs = Application.Storage.getValue("diag_msgs");
        var freememStart = Application.Storage.getValue("diag_free_mem_start");
        var freememStop = Application.Storage.getValue("diag_free_mem_stop");
        var lastStartTs = Application.Storage.getValue("diag_last_start_ts");
        var lastStopTs = Application.Storage.getValue("diag_last_stop_ts");
        var lastMsgTs = Application.Storage.getValue("diag_last_msg_ts");
        var lastMsgBytes = Application.Storage.getValue("diag_last_msg_bytes");
        var cbResolved = Application.Storage.getValue("diag_cb_resolved");
        var regOk = Application.Storage.getValue("diag_reg_ok");
        var nullCbAt = Application.Storage.getValue("diag_null_cb_at");
        var errPhase = Application.Storage.getValue("diag_err_phase");
        var errMsg = Application.Storage.getValue("diag_err_msg");

        var lines = "";

        lines = lines + "DIAGNOSTICS\n";
        lines = lines + "starts:" + valStr(starts) + " stops:" + valStr(stops) + " msgs:" + valStr(msgs) + "\n";
        lines = lines + "mem@start:" + valStr(freememStart) + "\n";
        lines = lines + "mem@stop:" + valStr(freememStop) + "\n";
        lines = lines + "cb:" + valStr(cbResolved) + " reg:" + valStr(regOk) + "\n";

        if (lastStartTs instanceof Number) {
            lines = lines + "start " + ageStr(now, lastStartTs as Number) + " ago\n";
        } else {
            lines = lines + "start:--\n";
        }
        if (lastStopTs instanceof Number) {
            lines = lines + "stop " + ageStr(now, lastStopTs as Number) + " ago\n";
        } else {
            lines = lines + "stop:--\n";
        }
        if (lastMsgTs instanceof Number) {
            lines = lines + "msg " + ageStr(now, lastMsgTs as Number) + " ago\n";
            lines = lines + "msg_bytes:" + valStr(lastMsgBytes) + "\n";
        } else {
            lines = lines + "msg:--\n";
        }
        if (nullCbAt instanceof Number) {
            lines = lines + "null_cb:" + ageStr(now, nullCbAt as Number) + " ago\n";
        }
        if (errPhase instanceof String) {
            lines = lines + "err:" + (errPhase as String) + "\n";
        }
        if (errMsg instanceof String) {
            lines = lines + (errMsg as String) + "\n";
        }

        var y = 30;
        var availH = dc.getHeight() - y - 20;
        var fitted = Graphics.fitTextToArea(lines, Graphics.FONT_XTINY, textW, availH, false);
        var toDraw = (fitted != null) ? fitted : lines;
        var area = new WatchUi.TextArea({
            :text => toDraw,
            :color => Graphics.COLOR_LT_GRAY,
            :font => Graphics.FONT_XTINY,
            :locX => cx - (textW / 2),
            :locY => y,
            :width => textW,
            :height => availH,
            :justification => Graphics.TEXT_JUSTIFY_LEFT
        });
        area.draw(dc);
    }

    function onHide() as Void {
    }

    //! Format a storage value as string, or "--" if null.
    private function valStr(v as Application.PropertyValueType) as String {
        if (v == null) {
            return "--";
        }
        if (v instanceof Number) {
            return (v as Number).toString();
        }
        if (v instanceof String) {
            return v as String;
        }
        return "?";
    }

    //! Format age in seconds as "Xs", "Xm", or "Xh".
    private function ageStr(now as Number, ts as Number) as String {
        var age = now - ts;
        if (age < 0) {
            return "0s";
        }
        if (age < 60) {
            return age.toString() + "s";
        }
        if (age < 3600) {
            return (age / 60).toString() + "m";
        }
        return (age / 3600).toString() + "h";
    }
}
