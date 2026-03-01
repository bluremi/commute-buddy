# Toybox.Communications API Reference

> Source: https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html
> SDK Version: Connect IQ 8.4.1 (System 8) — February 2026

## Module Overview

The Communications module enables widgets and apps to communicate via Bluetooth Low Energy (BLE) with mobile phones, allowing devices to bridge internet connectivity and participate in IoT ecosystems.

- **Availability:** API Level 1.0.0 (foreground data fields: 5.0.0)
- **Permission Required:** Communications

---

## Classes

- **ConnectionListener** — Handles connection state callbacks
- **MailboxIterator** — Iterates through mailbox messages *(deprecated)*
- **Message** — Represents a generic message (base class)
- **OAuthMessage** — Contains OAuth authentication responses
- **PhoneAppMessage** — Phone application messages (extends Message)
- **SyncDelegate** — Manages sync operations

---

## Key Classes for Phone-to-Watch Messaging

### Message

Base class for all messages. API Level 1.3.0.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `data` | `Lang.Object \| Null` | The data delivered by the message |

### PhoneAppMessage

Extends `Message`. Represents messages received through the phone app message callback system.

- **API Level:** 1.4.0
- **Inheritance:** `Lang.Object` → `Message` → `PhoneAppMessage`
- Access the payload via the inherited `data` property

### ConnectionListener

Provides callback functions for communications operations. API Level 1.0.0.

**Methods:**

| Method | Signature | Description |
|--------|-----------|-------------|
| `onComplete()` | `onComplete() as Void` | Called when operation completes successfully |
| `onError()` | `onError() as Void` | Called when operation encounters an error |

---

## Constants

### Error Codes

| Constant | Value | Description |
|----------|-------|-------------|
| `UNKNOWN_ERROR` | 0 | Unknown error |
| `BLE_ERROR` | -1 | Generic BLE error |
| `BLE_HOST_TIMEOUT` | -2 | Host response timeout |
| `BLE_SERVER_TIMEOUT` | -3 | Server response timeout |
| `BLE_NO_DATA` | -4 | Empty response |
| `BLE_REQUEST_CANCELLED` | -5 | System cancelled request |
| `BLE_QUEUE_FULL` | -101 | Too many pending requests |
| `BLE_REQUEST_TOO_LARGE` | -102 | Request data exceeds size limit |
| `BLE_CONNECTION_UNAVAILABLE` | -104 | No BLE connection |
| `NETWORK_REQUEST_TIMED_OUT` | -300 | Request timeout |
| `NETWORK_RESPONSE_TOO_LARGE` | -402 | Response exceeds size limit |
| `STORAGE_FULL` | -1000 | Insufficient storage |
| `SECURE_CONNECTION_REQUIRED` | -1001 | HTTPS required |

### HTTP Methods

| Constant | Value |
|----------|-------|
| `HTTP_REQUEST_METHOD_GET` | 1 |
| `HTTP_REQUEST_METHOD_PUT` | 2 |
| `HTTP_REQUEST_METHOD_POST` | 3 |
| `HTTP_REQUEST_METHOD_DELETE` | 4 |

### Response Content Types

| Constant | Value |
|----------|-------|
| `HTTP_RESPONSE_CONTENT_TYPE_JSON` | 0 |
| `HTTP_RESPONSE_CONTENT_TYPE_URL_ENCODED` | 1 |
| `HTTP_RESPONSE_CONTENT_TYPE_GPX` | 2 |
| `HTTP_RESPONSE_CONTENT_TYPE_FIT` | 3 |
| `HTTP_RESPONSE_CONTENT_TYPE_AUDIO` | 4 |
| `HTTP_RESPONSE_CONTENT_TYPE_TEXT_PLAIN` | 5 |
| `HTTP_RESPONSE_CONTENT_TYPE_HLS_DOWNLOAD` | 6 |

### Request Content Types

| Constant | Value |
|----------|-------|
| `REQUEST_CONTENT_TYPE_URL_ENCODED` | 0 |
| `REQUEST_CONTENT_TYPE_JSON` | 1 |

---

## Instance Methods

### Messaging (Phone ↔ Watch)

#### `registerForPhoneAppMessages(method)`

Registers callback for incoming phone app messages.

- **Parameters:** `method` (`PhoneMessageCallback` or `null`) — Handler receiving `PhoneAppMessage`
- **Returns:** `Void`
- **Since:** API 1.4.0

#### `transmit(content, options, listener)`

Sends data via BLE to connected mobile device.

- **Parameters:**
  - `content` (`PersistableType`) — Data object
  - `options` (`Dictionary` or `null`) — Future expansion (currently empty)
  - `listener` (`ConnectionListener`) — Transmission status callback
- **Returns:** `Void`
- **Since:** API 1.0.0

### Network Requests

#### `makeWebRequest(url, parameters, options, responseCallback)`

Initiates asynchronous HTTP request supporting multiple response types.

- **Parameters:**
  - `url` (`String`) — Target URL
  - `parameters` (`Dictionary` or `null`) — Unencoded query/body parameters
  - `options` (`Dictionary` or `null`) — Request configuration:
    - `:method` (`HttpRequestMethod`) — HTTP verb
    - `:headers` (`Dictionary`) — Custom headers
    - `:responseType` (`HttpResponseContentType`) — Expected response format
    - `:context` (`Object` or `null`) — User data passed to callback
    - `:maxBandwidth` (`Number`) — Maximum HLS stream bitrate
    - `:fileDownloadProgressCallback` (`Method`) — Download progress tracking
  - `responseCallback` (`Method`) — Handler receiving `(responseCode, data)` or `(responseCode, data, context)`
- **Returns:** `Void`
- **Since:** API 1.3.0

#### `makeImageRequest(url, parameters, options, responseCallback)`

Downloads and processes images with device-specific optimization.

- **Parameters:**
  - `url` (`String`) — Image URL
  - `parameters` (`Dictionary` or `null`) — Query parameters
  - `options` (`Dictionary`) — Image processing:
    - `:palette` (`Array<Number>`) — Color palette restriction
    - `:maxWidth` (`Number`) — Maximum width
    - `:maxHeight` (`Number`) — Maximum height
    - `:dithering` (`Dithering`) — Default: `FLOYD_STEINBERG`
    - `:packingFormat` (`PackingFormat`) — Default: `PACKING_FORMAT_DEFAULT`
  - `responseCallback` (`Method`) — Handler receiving `(responseCode, BitmapResource|BitmapReference|null)`
- **Returns:** `Void`
- **Since:** API 1.2.0

#### `cancelAllRequests()`

Cancels all pending JSON and image requests.

- **Returns:** `Void`
- **Since:** API 1.2.0

### OAuth & Authentication

#### `makeOAuthRequest(requestUrl, requestParams, resultUrl, resultType, resultKeys)`

Initiates OAuth 2.0 sign-in through Garmin Connect Mobile.

- **Parameters:**
  - `requestUrl` (`String`) — Authentication endpoint
  - `requestParams` (`Dictionary`) — Unencoded query parameters
  - `resultUrl` (`String`) — Final redirect page containing response
  - `resultType` (`TokenResult`) — `OAUTH_RESULT_TYPE_URL`
  - `resultKeys` (`Dictionary<String, String>`) — Maps OAuth response keys to callback keys
- **Returns:** `Void`
- **Requires:** BLE connection to mobile device
- **Since:** API 1.3.0

#### `registerForOAuthMessages(method)`

Registers callback to receive OAuth responses.

- **Parameters:** `method` (`Method`) — Handler receiving `OAuthMessage`
- **Returns:** `Void`
- **Since:** API 1.3.0

### Utilities

#### `openWebPage(url, params, options)`

Pushes phone notification to open URL in browser.

- **Requires:** BLE connection to mobile device
- **Since:** API 1.3.0

#### `encodeURL(url)`

Percent-encodes reserved characters per RFC 3986.

- **Parameters:** `url` (`String`)
- **Returns:** `String` (percent-encoded)
- **Since:** API 1.1.2

#### `checkWifiConnection(connectionStatusCallback)`

Tests internet-enabled Wi-Fi availability.

- **Parameters:** `connectionStatusCallback` (`Method`) — Handler receiving dictionary with `:wifiAvailable` (`Boolean`) and `:errorCode` (`WifiConnectionStatus`)
- **Returns:** `Void`
- **Since:** API 3.2.0

### Sync Operations

#### `startSync()`

Exits app and launches synchronization mode. Since API 3.1.0.

#### `startSync2(options)`

Exits app and launches sync with custom message. Options: `:message` (`String`). Since API 4.0.4.

#### `notifySyncProgress(percentageComplete)`

System notification of sync completion percentage (0–100). Since API 3.1.0.

#### `notifySyncComplete(errorMessage)`

System notification of sync completion. Pass `null` for success. Since API 3.1.0.

---

## Typedef

### `PhoneMessageCallback`

```monkeyc
Lang.Method(msg as Communications.PhoneAppMessage) as Void
```

Callback signature for phone app messages. Since API 1.0.0.

---

## Notes

- Deprecated methods (mailbox, OAuth 1.0a, `makeJsonRequest`) may be removed post System 4–10
- Web requests are asynchronous; response callbacks execute on completion
- BLE operations require mobile device connection
- Request parallelism is limited; use `cancelAllRequests()` to free capacity
