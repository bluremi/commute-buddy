import Toybox.Communications;
import Toybox.Lang;

//! No-op connection listener for ad-hoc POLL_NOW transmits (FEAT-16).
//! Communications.transmit() requires a ConnectionListener; in-flight and
//! failure feedback are explicitly out of scope for this story (deferred to a
//! future story), so success/error are intentionally silent — the status view
//! simply refreshes in place when the phone's reply arrives.
class PollRequestListener extends Communications.ConnectionListener {

    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
    }
}
