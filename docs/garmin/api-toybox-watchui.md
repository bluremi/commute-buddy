# Toybox.WatchUi API Reference

> Source: https://developer.garmin.com/connect-iq/api-docs/Toybox/WatchUi.html
> SDK Version: Connect IQ 8.4.1 (System 8) — February 2026

## Module Overview

The WatchUi module provides user interface elements for Connect IQ applications: Views (screen display), UI controls (menus, progress bars, buttons, pickers), and drawable objects (bitmaps, text).

- **API Level:** 1.0.0
- **Supported App Types:** Audio Content Provider, Background (5.1.0+), Data Field, Glance, Watch App, Watch Face, Widget

---

## Classes

### Core UI
- **View** — Base drawable container
- **GlanceView** — Specialized view for widget glance/preview display
- **WatchFace** — Watch face base class
- **Menu / Menu2** — Menu navigation
- **ActionMenu** — Quick action menu
- **Confirmation** — Confirmation dialog
- **ProgressBar** — Progress indicator
- **TextPicker / NumberPicker / Picker** — Input selection
- **MapView / MapTrackView** — Mapping interfaces
- **ViewLoop** — Continuous view rendering

### Drawables
- **Bitmap** — Image rendering
- **Text / TextArea** — Text display
- **Layer** — Layered drawable container
- **AnimationLayer** — Animation support
- **Button** — Interactive button

### Delegates (Input Handlers)
- **InputDelegate** — Base input handler
- **BehaviorDelegate** — Behavior-specific input
- **GlanceViewDelegate** — Glance event handling
- **MenuInputDelegate** — Menu input
- **ActionMenuDelegate** — Action menu input
- **WatchFaceDelegate** — Watch face input
- **ConfirmationDelegate** — Confirmation dialog handling

### Events
- **KeyEvent** — Key press events
- **ClickEvent** — Touch tap/hold events
- **SwipeEvent** — Swipe gestures
- **DragEvent** — Drag gestures

---

## Key Constants

### Key Events

| Constant | Value | Description |
|----------|-------|-------------|
| `KEY_POWER` | 0 | Power button |
| `KEY_LIGHT` | 1 | Light button |
| `KEY_ENTER` | 4 | Enter/confirm |
| `KEY_ESC` | 5 | Escape/back |
| `KEY_MENU` | 7 | Menu button |
| `KEY_UP` | 13 | Up directional |
| `KEY_DOWN` | 8 | Down directional |

### Transitions

| Constant | Value | Description |
|----------|-------|-------------|
| `SLIDE_IMMEDIATE` | 0 | No transition |
| `SLIDE_LEFT` | 1 | Leftward slide |
| `SLIDE_RIGHT` | 2 | Rightward slide |
| `SLIDE_UP` | 4 | Upward slide |
| `SLIDE_DOWN` | 3 | Downward slide |
| `SLIDE_BLINK` | 5 | Fade-in effect |

### Layout Alignment

| Constant | Value |
|----------|-------|
| `LAYOUT_HALIGN_LEFT` | 0 |
| `LAYOUT_HALIGN_CENTER` | 1 |
| `LAYOUT_HALIGN_RIGHT` | 2 |
| `LAYOUT_VALIGN_TOP` | 0 |
| `LAYOUT_VALIGN_CENTER` | 1 |
| `LAYOUT_VALIGN_BOTTOM` | 2 |

---

## Key Methods

### View Management

#### `pushView(view, delegate, transition)`
Adds a view to the stack with optional input handler. Returns `Boolean`.

#### `popView(transition)`
Removes the current view from stack.

#### `switchToView(view, delegate, transition)`
Replaces current view with a new one.

#### `getCurrentView()`
Returns `[View, InputDelegate]` array or nulls.

#### `requestUpdate()`
Triggers `onUpdate()` call on current view.

### Notifications

#### `showToast(text, options)`
Displays notification toast. Optional `:icon` parameter.

### Animation

#### `animate(object, property, type, start, stop, period, callback)`
Animates an object property over specified duration.

#### `cancelAllAnimations()`
Stops all active animations.

### Resources

#### `loadResource(resourceId)`
Loads compiled resources (strings, bitmaps, fonts, animations).

---

## View Class

Inheritance: `Lang.Object` → `View`

### Lifecycle

**Widgets and Watch Apps:** `onLayout()` → `onShow()` → `onUpdate()` → `onHide()`

**Watch Faces:** `onLayout()` → `onShow()` → `onUpdate()`

### Lifecycle Callbacks

#### `onLayout(dc)`
```monkeyc
onLayout(dc as Graphics.Dc) as Void
```
Entry point called before View displays. Load resources and configure layout here.

#### `onShow()`
```monkeyc
onShow() as Void
```
Called when View enters foreground. Load resources into system memory.

#### `onUpdate(dc)`
```monkeyc
onUpdate(dc as Graphics.Dc) as Void
```
Called after `onShow()`. Updates dynamic content. Invoked on `requestUpdate()` calls, periodic updates, or animation activity.

#### `onHide()`
```monkeyc
onHide() as Void
```
Called before View is removed from foreground. Free resources.

### Layout Methods

#### `setLayout(layout)`
```monkeyc
setLayout(layout as Array<Drawable> or Null) as Void
```
Establishes the array of Drawable objects managed by the View.

#### `findDrawableById(identifier)`
```monkeyc
findDrawableById(identifier as Object) as Drawable or Null
```
Retrieves a Drawable by its identifier.

### Layer Management (API 3.1.0+)

- `addLayer(layer)` — Adds Layer to top of stack
- `removeLayer(layer)` — Removes layer, returns `Boolean`
- `insertLayer(layer, idx)` — Inserts at position
- `getLayers()` — Returns copy of layer stack
- `clearLayers()` — Removes all layers

---

## GlanceView Class

Inheritance: `Lang.Object` → `View` → `GlanceView`

- **API Level:** 3.1.0
- Specialized view for displaying widget preview content in a restricted drawing context
- Supports `onLayout()` and `onUpdate()` lifecycle
- **Does NOT support:** Layers (`addLayer`, `removeLayer`, etc.) or page control

## GlanceViewDelegate Class

- **API Level:** 3.1.0
- Relays events during widget glance (preview) mode
- **Method:** `onGlanceEvent(options as Dictionary or Null) as Boolean`
