# Communicating with Mobile Apps

> Source: [Garmin Connect IQ Core Topics — Communicating with Mobile Apps](https://developer.garmin.com/connect-iq/core-topics/communicating-with-mobile-apps/)

There are a few complications with device-to-phone communication. For example, the watch app may be killed during the time communication happens, or a phone app may try to send information while the app is not active. In order to simplify these cases for the developer, Monkey C does not expose a low-level interface, but instead exposes a very high-level approach: the API exposes a **mailbox metaphor** instead of using a socket metaphor. Messages are constructed as a parcel of information and sent back and forth between devices. Each app will have a mailbox where messages are received, and an event that fires when new messages arrive.

---

## Mobile SDK Downloads

The Connect IQ Mobile SDKs are released separately from the Connect IQ Developer SDK and are available for iOS and Android. There are several editions of the Mobile SDK available:

- **Android BLE**
- **iOS BLE**
- **Android ADB**

The Bluetooth low energy edition supports development of communication-enabled applications on an iOS or Android target device, while the Android Debug Bridge (ADB) edition is used for testing with the Connect IQ Simulator.

More information on how to download the correct version for your mobile platform may be found on the [Garmin Developer site](https://developer.garmin.com/connect-iq/) and the [Mobile SDK for Android](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/) and [Mobile SDK for iOS](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-ios/) sections.

---

## BLE Simulation Over Android Debug Bridge

When using the Connect IQ Simulator, it is possible to communicate with a companion app running on an Android device using Android Debug Bridge. This will simulate actual Bluetooth low energy speeds to better approximate performance of your application.

The Android Debug Bridge edition of the Android Mobile SDK and companion app is required to use Android Debug Bridge for testing. Here's how to enable the companion to communicate over Android Debug Bridge:

1. Connect the phone to the PC running the simulator via USB
2. Have USB debugging enabled on the Android handset
3. Obtain an instance of `ConnectIQ` using `getInstance( IQCommProtocol.ADB_SIMULATOR )`
4. Optionally call `setAdbPort( int port )` to set a specific port to use for communication (the default port is 7381)
5. Call `initialize()`

To allow the simulator to communicate over Android Debug Bridge, forward the TCP port to the Android device in a terminal or console:

```bash
adb forward tcp:7381 tcp:7381
```

> **Note:** This command will need to be reissued for each connected Android device, or if a device is disconnected and re-connected.

Once your app is started on the phone, connect it to the simulator by clicking the **Connection** menu and selecting **Start** (`CTRL-F1`). The Connect IQ apps in the simulator will now be able to communicate with your device via the Communications APIs over Android Debug Bridge.

---

## The Communications Module

The [`Toybox.Communications`](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html) module provides tools for device communication via Bluetooth Low Energy (BLE) and internet connectivity. It enables widgets and apps to communicate with mobile phones, facilitating IoT capabilities.

- **API Level:** 1.0.0 (Data fields support added in 5.0.0)
- **Required Permission:** `Communications`
- **Supported Contexts:** Audio Content Provider, Background, Data Field, Glance, Watch App, Widget

### Sending Data to the Phone (transmit)

The `transmit()` method sends data across the BLE link to a companion phone application. You provide the content to send, an options dictionary, and a `ConnectionListener` to handle success or failure callbacks.

```monkeyc
Communications.transmit(content, options, listener);
```

#### Method Signature

```monkeyc
transmit(
    content as Application.PersistableType,
    options as Lang.Dictionary or Null,
    listener as Communications.ConnectionListener
) as Void
```

**Parameters:**

| Parameter  | Type                                | Description                                                                 |
|------------|-------------------------------------|-----------------------------------------------------------------------------|
| `content`  | `Application.PersistableType`       | The object to send over BLE                                                 |
| `options`  | `Lang.Dictionary` or `Null`         | Transmit options (currently an empty Dictionary, reserved for future use)    |
| `listener` | `Communications.ConnectionListener` | A `ConnectionListener` subclass instance to handle transmission callbacks   |

**Since:** API Level 1.0.0

#### ConnectionListener

The [`ConnectionListener`](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications/ConnectionListener.html) class serves as the callback interface for communications operations. Subclass it and override `onComplete()` and `onError()` to handle results.

- **Inherits from:** `Toybox.Lang.Object`
- **Since:** API Level 1.0.0

| Method         | Signature            | Description                                        |
|----------------|----------------------|----------------------------------------------------|
| `onComplete()` | `onComplete() as Void` | Called when the communications operation completes successfully |
| `onError()`    | `onError() as Void`    | Called when a communications operation error occurs |

**Example — Sending data with a ConnectionListener:**

```monkeyc
using Toybox.Communications as Comm;

class CommListener extends Comm.ConnectionListener {
    function initialize() {
        Comm.ConnectionListener.initialize();
    }

    function onComplete() {
        System.println("Transmit Complete");
    }

    function onError() {
        System.println("Transmit Failed");
    }
}

// Send a message to the companion phone app
var data = { "key" => "value" };
Comm.transmit(data, null, new CommListener());
```

### Receiving Data from the Phone (registerForPhoneAppMessages)

To receive messages from a companion phone app, register a callback using `registerForPhoneAppMessages()`. The callback is invoked once per received message. If messages are already waiting when the function is called, the callback is immediately invoked for each waiting message.

#### Method Signature

```monkeyc
registerForPhoneAppMessages(
    method as PhoneMessageCallback or Null
) as Void
```

**Parameters:**

| Parameter | Type                                 | Description                                        |
|-----------|--------------------------------------|----------------------------------------------------|
| `method`  | `PhoneMessageCallback` or `Null`     | Callback method receiving a `PhoneAppMessage` argument |

**Since:** API Level 1.4.0

**PhoneMessageCallback typedef:**

```monkeyc
Lang.Method(msg as Communications.PhoneAppMessage) as Void
```

#### PhoneAppMessage

The [`PhoneAppMessage`](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications/PhoneAppMessage.html) class represents messages received from the connected phone app.

- **Inherits from:** `Toybox.Communications.Message` > `Toybox.Lang.Object`
- **Since:** API Level 1.4.0

The base [`Message`](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications/Message.html) class provides a single property:

| Property | Type                      | Description                      |
|----------|---------------------------|----------------------------------|
| `data`   | `Lang.Object` or `Null`   | The data delivered by the message |

**Example — Receiving phone app messages:**

```monkeyc
using Toybox.Communications as Comm;

var phoneMessage;

function phoneMessageCallback(msg) {
    phoneMessage = msg.data;
}

Comm.registerForPhoneAppMessages(method(:phoneMessageCallback));
```

### Deprecated Mailbox API

The original mailbox API is deprecated in favor of `registerForPhoneAppMessages()`. The following methods may be removed after System 4:

| Method              | Description                                     |
|---------------------|-------------------------------------------------|
| `setMailboxListener(listener)` | Add a listener for mailbox events. Listener is called whenever a new message is received. |
| `getMailbox()`      | Get the `MailboxIterator` for this application's mailbox. |
| `emptyMailbox()`    | Clear the contents of the mailbox.              |

---

## Web Requests

The Communications module provides methods for making HTTP requests from the watch, proxied through the connected phone (over BLE) or directly over WiFi on supported devices.

### makeWebRequest

Initiate an asynchronous download request. The `responseCallback` is invoked on completion. Works over WiFi or Bluetooth.

#### Method Signature

```monkeyc
makeWebRequest(
    url as Lang.String,
    parameters as Lang.Dictionary<Lang.Object, Lang.Object> or Null,
    options as {
        :method as HttpRequestMethod,
        :headers as Lang.Dictionary,
        :responseType as HttpResponseContentType,
        :context as Lang.Object or Null,
        :maxBandwidth as Lang.Number,
        :fileDownloadProgressCallback as Lang.Method(
            totalBytesTransferred as Lang.Number,
            fileSize as Lang.Number or Null
        ) as Void
    } or Null,
    responseCallback as Lang.Method(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or
             PersistedContent.Iterator or Null
    ) as Void or Lang.Method(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or
             PersistedContent.Iterator or Null,
        context as Lang.Object
    ) as Void
) as Void
```

**Parameters:**

| Parameter          | Type         | Description |
|--------------------|--------------|-------------|
| `url`              | `String`     | The URL to request |
| `parameters`       | `Dictionary` or `Null` | Key-value pairs (not URL encoded). For GET/DELETE: appended to URL. For POST/PUT: set as body. |
| `options`          | `Dictionary` or `Null` | Request configuration (see options table below) |
| `responseCallback` | `Method`     | Callback receiving `responseCode` and `data` (and optionally `context`) |

**Options dictionary keys:**

| Key               | Type                        | Description |
|-------------------|-----------------------------|-------------|
| `:method`         | `HttpRequestMethod`         | One of `HTTP_REQUEST_METHOD_GET`, `_PUT`, `_POST`, `_DELETE` |
| `:headers`        | `Lang.Dictionary`           | HTTP headers. Content-Type via `REQUEST_CONTENT_TYPE_*` constants |
| `:responseType`   | `HttpResponseContentType`   | Expected response format. System uses server Content-Type if omitted |
| `:context`        | `Lang.Object` or `Null`     | User-specific context passed to a 3-parameter callback |
| `:maxBandwidth`   | `Lang.Number`               | Maximum bandwidth for HLS stream selection |
| `:fileDownloadProgressCallback` | `Method`      | Progress callback (API 3.2.0+, media files only) |

**Behavior notes:**

- Default Content-Type: `application/json` for GET/DELETE, `application/x-www-form-urlencoded` for POST/PUT
- DELETE with a Content-Type header: parameters go in the body, not appended to the URL
- GET: parameters are always appended to the URL
- FIT/GPX `responseType`: system attempts to download and parse the files
- Unknown Content-Type header in response: returns an error
- Throws `InvalidOptionsException` if a required option is missing
- Throws `SymbolNotAllowedException` if the `responseType` is unsupported on the device

**Since:** API Level 1.3.0

**Example:**

```monkeyc
using Toybox.Communications as Comm;

function onReceive(responseCode, data) {
    if (responseCode == 200) {
        System.println("Request Successful");
    } else {
        System.println("Response: " + responseCode);
    }
}

function makeRequest() {
    var url = "https://www.garmin.com";

    var params = {
        "definedParams" => "123456789abcdefg"
    };

    var options = {
        :method => Comm.HTTP_REQUEST_METHOD_GET,
        :headers => {
            "Content-Type" => Comm.REQUEST_CONTENT_TYPE_URL_ENCODED
        },
        :responseType => Comm.HTTP_RESPONSE_CONTENT_TYPE_URL_ENCODED
    };

    Comm.makeWebRequest(url, params, options, method(:onReceive));
}
```

### makeImageRequest

Initiate an image download request. Garmin Connect Mobile scales and dithers images based on device capabilities. Works over WiFi or Bluetooth.

#### Method Signature

```monkeyc
makeImageRequest(
    url as Lang.String,
    parameters as Lang.Dictionary or Null,
    options as {
        :palette as Lang.Array<Lang.Number>,
        :maxWidth as Lang.Number,
        :maxHeight as Lang.Number,
        :dithering as Dithering,
        :packingFormat as PackingFormat
    },
    responseCallback as Lang.Method(
        responseCode as Lang.Number,
        data as WatchUi.BitmapResource or Graphics.BitmapReference or Null
    ) as Void
) as Void
```

**Parameters:**

| Parameter          | Type         | Description |
|--------------------|--------------|-------------|
| `url`              | `String`     | The image URL |
| `parameters`       | `Dictionary` or `Null` | Parameters appended to URL |
| `options`          | `Dictionary`  | Image processing options (see below) |
| `responseCallback` | `Method`     | Callback receives `responseCode` and `BitmapResource`/`BitmapReference` (or `null` on error) |

**Options dictionary keys:**

| Key              | Type          | Description |
|------------------|---------------|-------------|
| `:palette`       | `Array<Number>` | Color palette for dithering |
| `:maxWidth`      | `Number`      | Maximum width for scaling |
| `:maxHeight`     | `Number`      | Maximum height for scaling |
| `:dithering`     | `Dithering`   | Dithering algorithm (default: `IMAGE_DITHERING_FLOYD_STEINBERG`) |
| `:packingFormat` | `PackingFormat` | Encoding format (default: `PACKING_FORMAT_DEFAULT`) |

**Since:** API Level 1.2.0

**Example:**

```monkeyc
using Toybox.Communications as Comm;
using Toybox.Graphics as Gfx;

var image;

function responseCallback(responseCode, data) {
    if (responseCode == 200) {
        image = data;
    } else {
        image = null;
    }
}

var url = "http://www.garmin.com/image-path";
var parameters = null;
var options = {
    :palette => [Gfx.COLOR_ORANGE, Gfx.COLOR_DK_BLUE,
                 Gfx.COLOR_BLUE, Gfx.COLOR_BLACK],
    :maxWidth => 100,
    :maxHeight => 100,
    :dithering => Comm.IMAGE_DITHERING_NONE
};

Comm.makeImageRequest(url, parameters, options, method(:responseCallback));
```

### cancelAllRequests

Cancel all pending JSON and image requests. The Connect IQ platform limits the number of active requests running in parallel.

```monkeyc
cancelAllRequests() as Void
```

**Since:** API Level 1.2.0

---

## OAuth Authentication

### makeOAuthRequest

Request an OAuth sign-in through Garmin Connect Mobile. This triggers a phone notification with a web view for authentication. Upon user approval, the callback registered with `registerForOAuthMessages()` receives an `OAuthMessage`.

> **Note:** Only works over a Bluetooth connection to a mobile device.

#### Method Signature

```monkeyc
makeOAuthRequest(
    requestUrl as Lang.String,
    requestParams as Lang.Dictionary,
    resultUrl as Lang.String,
    resultType as TokenResult,
    resultKeys as Lang.Dictionary<Lang.String, Lang.String>
) as Void
```

**Parameters:**

| Parameter       | Type                                    | Description |
|-----------------|-----------------------------------------|-------------|
| `requestUrl`    | `String`                                | URL to load in the authentication web view |
| `requestParams` | `Dictionary`                            | Non-URL encoded parameters for `requestUrl` |
| `resultUrl`     | `String`                                | Final authentication page URL containing `resultKeys` |
| `resultType`    | `TokenResult`                           | `OAUTH_RESULT_TYPE_URL` — format specifier |
| `resultKeys`    | `Dictionary<String, String>`            | Maps OAuth response keys to `OAuthMessage` data keys |

**Since:** API Level 1.3.0

### registerForOAuthMessages

Register a callback for receiving OAuth messages. The callback is invoked once per received OAuth message. If messages are already waiting when the function is called, the callback is immediately invoked for each waiting message.

```monkeyc
registerForOAuthMessages(
    method as Lang.Method(data as Communications.OAuthMessage) as Void
) as Void
```

**Since:** API Level 1.3.0

### OAuth Example

```monkeyc
using Toybox.Communications as Comm;
using Toybox.WatchUi as Ui;

const CLIENT_ID = "myClientID";
const OAUTH_CODE = "myOAuthCode";
const OAUTH_ERROR = "myOAuthError";

var status;

Comm.registerForOAuthMessages(method(:onOAuthMessage));

function getOAuthToken() {
    status = "Look at OAuth screen\n";
    Ui.requestUpdate();

    var params = {
        "scope" => Comm.encodeURL("https://www.serviceurl.com/"),
        "redirect_uri" => "https://localhost",
        "response_type" => "code",
        "client_id" => $.CLIENT_ID
    };

    Comm.makeOAuthRequest(
        "https://requesturl.com",
        params,
        "http://resulturl.com",
        Comm.OAUTH_RESULT_TYPE_URL,
        {"responseCode" => $.OAUTH_CODE,
         "responseError" => $.OAUTH_ERROR}
    );
}

function onOAuthMessage(message) {
    if (message.data != null) {
        var code = message.data[$.OAUTH_CODE];
        var error = message.data[$.OAUTH_ERROR];
    } else {
        // Handle error
    }
}
```

---

## Opening Web Pages

### openWebPage

Request that Garmin Connect Mobile issue a phone notification that will open a web page. If the user accepts the notification, the web page opens in the phone's default browser.

> **Note:** Only works over a Bluetooth connection to a mobile device.

```monkeyc
openWebPage(
    url as Lang.String,
    params as Lang.Dictionary or Null,
    options as Lang.Dictionary or Null
) as Void
```

**Since:** API Level 1.3.0

**Example:**

```monkeyc
Communications.openWebPage(
    "http://www.bing.com/images/search",
    {"q" => "cute kitten"},
    null
);
// Opens: http://bing.com/images/search?q=cute kitten
```

---

## WiFi Connectivity

### checkWifiConnection

Check if an internet-enabled WiFi access point is visible and connectable. This is useful on devices that support direct WiFi connectivity.

```monkeyc
checkWifiConnection(
    connectionStatusCallback as Lang.Method(
        result as {
            :wifiAvailable as Lang.Boolean,
            :errorCode as WifiConnectionStatus
        }
    ) as Void
) as Void
```

The result dictionary contains:

| Key              | Type                 | Description |
|------------------|----------------------|-------------|
| `:wifiAvailable` | `Boolean`            | `true` if an access point with internet access is available |
| `:errorCode`     | `WifiConnectionStatus` | Error code if unavailable |

**Since:** API Level 3.2.0

---

## Sync Mode

Sync mode allows applications to perform extended data synchronization operations.

### startSync

Exit the `AppBase` and relaunch in sync mode.

```monkeyc
startSync() as Void
```

**Since:** API Level 3.1.0

### startSync2

Exit the `AppBase` and relaunch in sync mode with a custom message displayed to the user.

```monkeyc
startSync2(
    options as {
        :message as Lang.String
    } or Null
) as Void
```

**Since:** API Level 4.0.4

### notifySyncProgress

Send a system notification to indicate overall sync progress.

```monkeyc
notifySyncProgress(percentageComplete as Lang.Number) as Void
```

**Parameters:**

| Parameter            | Type     | Description                     |
|----------------------|----------|---------------------------------|
| `percentageComplete` | `Number` | Completion percentage (0–100) |

**Since:** API Level 3.1.0

### notifySyncComplete

Send a system notification to indicate that the sync completed.

```monkeyc
notifySyncComplete(errorMessage as Lang.String or Null) as Void
```

**Parameters:**

| Parameter      | Type                | Description                                       |
|----------------|---------------------|---------------------------------------------------|
| `errorMessage` | `String` or `Null`  | Descriptive error message on failure; `null` on success |

**Since:** API Level 3.1.0

---

## Utility Methods

### encodeURL

Convert a URL string into a percent-encoded string. Replaces reserved characters with hex-value pairs per [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986).

```monkeyc
encodeURL(url as Lang.String) as Lang.String
```

**Since:** API Level 1.1.2

### generateSignedOAuthHeader (Deprecated)

> **Deprecated** — may be removed after System 10. Use OAuth 2.0 with `makeOAuthRequest()` instead.

Generate the value for the `Authorization` header in an OAuth 1.0a request.

```monkeyc
generateSignedOAuthHeader(
    url as Lang.String,
    params as Lang.Dictionary<Lang.String, Lang.Object>,
    requestMethod as HttpRequestMethod,
    signatureMethod as SigningMethod,
    token as Lang.String or Null,
    tokenSecret as Lang.String,
    consumerKey as Lang.String,
    consumerSecret as Lang.String
) as Lang.String
```

**Since:** API Level 1.3.0

---

## Constants Reference

### HTTP Request Methods

| Constant                       | Value | Description |
|--------------------------------|-------|-------------|
| `HTTP_REQUEST_METHOD_GET`      | 1     | HTTP GET    |
| `HTTP_REQUEST_METHOD_PUT`      | 2     | HTTP PUT    |
| `HTTP_REQUEST_METHOD_POST`     | 3     | HTTP POST   |
| `HTTP_REQUEST_METHOD_DELETE`   | 4     | HTTP DELETE  |

### Request Content Types

| Constant                            | Value | Description                              |
|-------------------------------------|-------|------------------------------------------|
| `REQUEST_CONTENT_TYPE_URL_ENCODED`  | 0     | `application/x-www-form-urlencoded`      |
| `REQUEST_CONTENT_TYPE_JSON`         | 1     | `application/json`                       |

### HTTP Response Content Types

| Constant                                        | Value | Description                     |
|-------------------------------------------------|-------|---------------------------------|
| `HTTP_RESPONSE_CONTENT_TYPE_JSON`               | 0     | `application/json`              |
| `HTTP_RESPONSE_CONTENT_TYPE_URL_ENCODED`        | 1     | `application/x-www-form-urlencoded` |
| `HTTP_RESPONSE_CONTENT_TYPE_GPX`                | 2     | GPX file format                 |
| `HTTP_RESPONSE_CONTENT_TYPE_FIT`                | 3     | FIT file format                 |
| `HTTP_RESPONSE_CONTENT_TYPE_AUDIO`              | 4     | `audio/*`                       |
| `HTTP_RESPONSE_CONTENT_TYPE_TEXT_PLAIN`          | 5     | `text/plain`                    |
| `HTTP_RESPONSE_CONTENT_TYPE_HLS_DOWNLOAD`       | 6     | HLS data type                   |
| `HTTP_RESPONSE_CONTENT_TYPE_ANIMATION_MANIFEST` | 7     | CIQ animation manifest          |
| `HTTP_RESPONSE_CONTENT_TYPE_ANIMATION`           | 8     | CIQ animation                   |

### Error Codes

| Constant                                            | Value  | Description                              |
|-----------------------------------------------------|--------|------------------------------------------|
| `UNKNOWN_ERROR`                                     | 0      | Unknown error occurred                   |
| `BLE_ERROR`                                         | -1     | Generic BLE error                        |
| `BLE_HOST_TIMEOUT`                                  | -2     | Host response timeout                    |
| `BLE_SERVER_TIMEOUT`                                | -3     | Server response timeout                  |
| `BLE_NO_DATA`                                       | -4     | Response contained no data               |
| `BLE_REQUEST_CANCELLED`                             | -5     | Request cancelled by system              |
| `BLE_QUEUE_FULL`                                    | -101   | Too many requests queued                 |
| `BLE_REQUEST_TOO_LARGE`                             | -102   | Serialized input too large               |
| `BLE_UNKNOWN_SEND_ERROR`                            | -103   | Send failed for unknown reason           |
| `BLE_CONNECTION_UNAVAILABLE`                        | -104   | No BLE connection available              |
| `INVALID_HTTP_HEADER_FIELDS_IN_REQUEST`             | -200   | Invalid HTTP headers in request          |
| `INVALID_HTTP_BODY_IN_REQUEST`                      | -201   | Invalid HTTP body in request             |
| `INVALID_HTTP_METHOD_IN_REQUEST`                    | -202   | Invalid HTTP method in request           |
| `NETWORK_REQUEST_TIMED_OUT`                         | -300   | Request timeout                          |
| `INVALID_HTTP_BODY_IN_NETWORK_RESPONSE`             | -400   | Invalid response body                    |
| `INVALID_HTTP_HEADER_FIELDS_IN_NETWORK_RESPONSE`    | -401   | Invalid response headers                 |
| `NETWORK_RESPONSE_TOO_LARGE`                        | -402   | Response too large for device            |
| `NETWORK_RESPONSE_OUT_OF_MEMORY`                    | -403   | Out of memory processing response        |
| `STORAGE_FULL`                                      | -1000  | Filesystem too full                      |
| `SECURE_CONNECTION_REQUIRED`                        | -1001  | HTTPS required                           |
| `UNSUPPORTED_CONTENT_TYPE_IN_RESPONSE`              | -1002  | Unsupported content type in response     |
| `REQUEST_CANCELLED`                                 | -1003  | HTTP request cancelled                   |
| `REQUEST_CONNECTION_DROPPED`                        | -1004  | Connection lost during request           |
| `UNABLE_TO_PROCESS_MEDIA`                           | -1005  | Media file unreadable                    |
| `UNABLE_TO_PROCESS_IMAGE`                           | -1006  | Image processing failed                  |
| `UNABLE_TO_PROCESS_HLS`                             | -1007  | HLS download failed                      |

### Image Packing Formats

| Constant                 | Value | Description                              |
|--------------------------|-------|------------------------------------------|
| `PACKING_FORMAT_DEFAULT` | 0     | Device native format (lossless)          |
| `PACKING_FORMAT_YUV`     | 1     | YUV format (lossy, compressed)           |
| `PACKING_FORMAT_PNG`     | 2     | PNG format (lossless, compressed)        |
| `PACKING_FORMAT_JPG`     | 3     | JPG format (lossy, compressed)           |

### Image Dithering

| Constant                          | Value | Description         |
|-----------------------------------|-------|---------------------|
| `IMAGE_DITHERING_NONE`            | 1     | No dithering        |
| `IMAGE_DITHERING_FLOYD_STEINBERG` | 2     | Floyd-Steinberg dithering |

### OAuth Constants

| Constant                          | Value | Description                    |
|-----------------------------------|-------|--------------------------------|
| `OAUTH_RESULT_TYPE_URL`           | 0     | OAuth token returned in URL    |
| `OAUTH_SIGNING_METHOD_HMAC_SHA1`  | 0     | HMAC-SHA1 signing method       |

### WiFi Connection Status Codes

| Constant                                            | Value | Description                         |
|-----------------------------------------------------|-------|-------------------------------------|
| `WIFI_CONNECTION_STATUS_LOW_BATTERY`                | 1     | Battery too low for WiFi            |
| `WIFI_CONNECTION_STATUS_NO_ACCESS_POINTS`           | 2     | No stored access points             |
| `WIFI_CONNECTION_STATUS_UNSUPPORTED`                | 3     | WiFi not supported on device        |
| `WIFI_CONNECTION_STATUS_USER_DISABLED`              | 4     | WiFi disabled by user               |
| `WIFI_CONNECTION_STATUS_BATTERY_SAVER_ACTIVE`       | 5     | Battery saver mode enabled          |
| `WIFI_CONNECTION_STATUS_STEALTH_MODE_ACTIVE`        | 6     | Stealth mode enabled                |
| `WIFI_CONNECTION_STATUS_AIRPLANE_MODE_ACTIVE`       | 7     | Airplane mode enabled               |
| `WIFI_CONNECTION_STATUS_POWERED_DOWN`               | 8     | WiFi radio powered down             |
| `WIFI_CONNECTION_STATUS_UNKNOWN`                    | 9     | Unknown WiFi status                 |
| `WIFI_CONNECTION_STATUS_CANNOT_CONNECT_TO_ACCESS_POINT` | 10 | Cannot connect to saved access point |
| `WIFI_CONNECTION_STATUS_TRANSFER_ALREADY_IN_PROGRESS`   | 11 | Transfer already in progress        |

---

## See Also

- [Toybox.Communications API Reference](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html)
- [Mobile SDK for Android](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/)
- [Mobile SDK for iOS](https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-ios/)
- [JSON REST Requests](https://developer.garmin.com/connect-iq/core-topics/https/)
- [Authenticated Web Services](https://developer.garmin.com/connect-iq/core-topics/authenticated-web-services/)
- [Connect IQ FAQ — How Do I Use the Connect IQ Mobile SDK](https://developer.garmin.com/connect-iq/connect-iq-faq/how-do-i-use-the-connect-iq-mobile-sdk/)
