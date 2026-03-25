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
        var freememStartMin = Application.Storage.getValue("diag_min_free_mem_start");
        var freememStop = Application.Storage.getValue("diag_free_mem_stop");
        var lastStartTs = Application.Storage.getValue("diag_last_start_ts");
        var lastStopTs = Application.Storage.getValue("diag_last_stop_ts");
        var lastMsgTs = Application.Storage.getValue("diag_last_msg_ts");
        var lastMsgBytes = Application.Storage.getValue("diag_last_msg_bytes");
        var freememMsg = Application.Storage.getValue("diag_free_mem_msg");
        var cbResolved = Application.Storage.getValue("diag_cb_resolved");
        var regOk = Application.Storage.getValue("diag_reg_ok");
        var nullCbAt = Application.Storage.getValue("diag_null_cb_at");
        var errPhase = Application.Storage.getValue("diag_err_phase");
        var errMsg = Application.Storage.getValue("diag_err_msg");

        var startAge = (lastStartTs instanceof Number) ? ageStr(now, lastStartTs as Number) : "--";
        var stopAge = (lastStopTs instanceof Number) ? ageStr(now, lastStopTs as Number) : "--";
        var msgAge = (lastMsgTs instanceof Number) ? ageStr(now, lastMsgTs as Number) : "--";

        var lines = "DIAGNOSTICS\n";
        lines = lines + "st:" + valStr(starts) + " | sp:" + valStr(stops) + " | msg:" + valStr(msgs) + "\n";
        lines = lines + "mem_s:" + valStr(freememStart) + " | min:" + valStr(freememStartMin) + "\n";
        lines = lines + "mem_p:" + valStr(freememStop) + " | mem_m:" + valStr(freememMsg) + "\n";
        lines = lines + "cb:" + valStr(cbResolved) + " | reg:" + valStr(regOk) + "\n";
        lines = lines + "s:" + startAge + " | p:" + stopAge + " | m:" + msgAge + "\n";
        lines = lines + "bytes:" + valStr(lastMsgBytes);
        if (nullCbAt instanceof Number) {
            lines = lines + "\nnull_cb:" + ageStr(now, nullCbAt as Number);
        }
        if (errPhase instanceof String) {
            lines = lines + "\nerr:" + (errPhase as String);
        }
        if (errMsg instanceof String) {
            lines = lines + "\n" + (errMsg as String);
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
