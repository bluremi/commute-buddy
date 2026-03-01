# Toybox.Application API Reference

> Source: https://developer.garmin.com/connect-iq/api-docs/Toybox/Application.html
> SDK Version: Connect IQ 8.4.1 (System 8) — February 2026

## Module Overview

The Application module provides the foundational architecture for Connect IQ apps: lifecycle management via AppBase, persistent storage via Storage, and property management via Properties.

---

## Modules

- **Application.Properties** — Property management (settings synced via Garmin Connect)
- **Application.Storage** — Persistent on-device data storage
- **Application.WatchFaceConfig** — Watch face configuration

---

## AppBase Class

The base class for all Connect IQ applications. Controls app lifecycle.

### Lifecycle Methods

#### `onStart(state)`
```monkeyc
onStart(state as Dictionary or Null) as Void
```
Called during initialization, before the initial view displays. The `state` parameter may contain launch context (resume status, glance origin, complication index).

#### `onStop(state)`
```monkeyc
onStop(state as Dictionary or Null) as Void
```
Called when the application terminates. Persist data here.

#### `getInitialView()` — **Must Override**
```monkeyc
getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates]
```
Required. Provides the initial View and optional InputDelegate. Failure to override causes a crash.

#### `getGlanceView()`
```monkeyc
getGlanceView() as [WatchUi.GlanceView] or
                    [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null
```
Supplies the GlanceView for widget preview display. Returns `null` to display app name as preview.

#### `onSettingsChanged()`
```monkeyc
onSettingsChanged() as Void
```
Triggered when settings change via Garmin Connect Mobile during active execution. Typically calls `WatchUi.requestUpdate()`.

#### `getServiceDelegate()`
```monkeyc
getServiceDelegate() as [System.ServiceDelegate]
```
Returns a ServiceDelegate for background tasks. Background operations auto-terminate after 30 seconds.

#### `onActive(state)`
```monkeyc
onActive(state as Dictionary or Null) as Void
```
Called when app enters foreground with full system resource access.

#### `onInactive(state)`
```monkeyc
onInactive(state as Dictionary or Null) as Void
```
Called when app moves to background; resource access becomes limited.

#### `onBackgroundData(data)`
```monkeyc
onBackgroundData(data as PersistableType) as Void
```
Receives data payload from background ServiceDelegate upon completion.

#### `onStorageChanged()`
```monkeyc
onStorageChanged() as Void
```
Notifies app when storage updates occur from parallel app instances (foreground/background sync).

### Utility Methods

#### `getApp()`
```monkeyc
Application.getApp() as AppBase
```
Returns the currently executing AppBase instance.

---

## Application.Storage Module

Persistent disk storage for applications. **API Level 2.4.0.**

### `getValue(key)`
```monkeyc
getValue(key as PropertyKeyType) as PropertyValueType
```
Retrieves data by key. Returns `null` if key doesn't exist.

- **Key types:** `String`, `Number`, `Float`, `Boolean`, `Char`, `Long`, `Double`
- **Throws:** `UnexpectedTypeException` for invalid key type

### `setValue(key, value)`
```monkeyc
setValue(key as PropertyKeyType, value as PropertyValueType) as Void
```
Stores data persistently.

- **Value types:** `String`, `Number`, `Float`, `Boolean`, `Char`, `Long`, `Double`, `BitmapResource`, `AnimationResource`, `null`, or `Array`/`Dictionary` containing these types
- **Size limit:** Individual values capped at **32 KB**; total device storage varies
- **Throws:** `UnexpectedTypeException`, `StorageFullException`, `ObjectStoreAccessException`

### `deleteValue(key)`
```monkeyc
deleteValue(key as PropertyKeyType) as Void
```
Removes a key-value pair.

### `clearValues()`
```monkeyc
clearValues() as Void
```
Erases all stored data for the application.

---

## Type Definitions

| Type | Definition |
|------|-----------|
| `PersistableType` | Equivalent to `PropertyValueType` |
| `PropertyKeyType` | `Number \| Float \| Long \| Double \| String \| Boolean \| Char` |
| `PropertyValueType` | Scalar types, Arrays, Dictionaries, Bitmaps, or Null |
