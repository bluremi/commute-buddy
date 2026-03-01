# Glances

> Since API Level 3.1.0

The intention of widgets was to make various kinds of information that are important to the user available at a glance. While the app carousel does allow quick navigation, navigating the loop may require launching numerous applications to get to what you're looking for. It also dedicates the entire screen for a given context.

Glances, introduced with the fenix 6 device, turn the presentation of widgets into a dashboard. Instead of a carousel, the user is presented with a list of metrics. Selecting a list item will launch the widget. API Level 3.1.0 added the ability for developers to support this feature.

## Relevant APIs

| API | Purpose | API Level |
|-----|---------|-----------|
| [`AppBase.getGlanceView()`](https://developer.garmin.com/connect-iq/api-docs/Toybox/Application/AppBase.html) | Called by the system to retrieve your glance | 3.1.0 |
| [`WatchUi.GlanceView`](https://developer.garmin.com/connect-iq/api-docs/Toybox/WatchUi/GlanceView.html) | Implementation of your application glance view on the glance list | 3.1.0 |

## How to Enable Glance Support in a Widget

The `WatchUi.GlanceView` allows an app to implement a Glance. Think of the `WatchUi.GlanceView` as a small canvas to present an executive summary from your widget. The `WatchUi.GlanceView` is similar to other `WatchUi.View` objects but should only be used for implementing a Glance. Override `AppBase.getGlanceView()` and return your implementation of `WatchUi.GlanceView` to add Glance support.

In devices before API Level 4.0.0, if a widget doesn't override `AppBase.getGlanceView()`, a default `WatchUi.GlanceView` will be used, which simply shows the name of the widget. **In API Level 4.0.0 and above, apps and widgets must implement a glance view to appear in the glance list.**

## How Widgets Start on the "Glance Page"

When a widget is shown as part of the "Glance Page", it will be started in Glance mode with **limited memory allocated (32KB for most devices)**.

Similar to [background services](https://developer.garmin.com/connect-iq/core-topics/background-services/), developers can use the `:glance` annotation to indicate which modules and/or classes are necessary when running a Widget in Glance mode. Just like background services, using the annotation selectively allows developers to limit memory usage when running in glance mode.

When designing widgets and widget glances, it's best to think of them as somewhat independent items that will function together. There is no guarantee that a widget will always start in glance mode, for example, before transitioning to its standard widget mode.

## Glance Lifecycle

Depending on a device's resource limitations, `WatchUi.GlanceView` can be updated in two different ways.

### Live UI Update

Devices that have ample resources<sup>1</sup> will start the Widget in Glance mode, and keep it alive. The provided `WatchUi.GlanceView` will be updated as needed by the system, and calls to `WatchUi.requestUpdate()` will trigger a `WatchUi.View` update as expected.

> **Note:** It's highly recommended that the update rate should be kept under 1Hz to provide a better scrolling experience.

### Background UI Update

Devices that have less memory<sup>2</sup> will start the app only when the system deems it appropriate, and calls to `WatchUi.requestUpdate()` will have no effect. Such a device could update their glance view when it becomes visible (activated) and at least 30 seconds since the last update.

During a background update, the Widget and its `WatchUi.GlanceView` will run through a **complete lifecycle**. The following functions are called in order:

1. `AppBase.onStart()` — starts the app
2. `AppBase.getGlanceView()` — retrieves the glance view
3. `View.onLayout()` — lays out the view
4. `View.onShow()` — the view is shown
5. `View.onUpdate()` — the view draws its content
6. `View.onHide()` — the view is hidden
7. `AppBase.onStop()` — the app is terminated and shut down

All content that is rendered to the `Graphics.Dc` passed to `View.onUpdate()` will be cached on the filesystem and used for display until the next time the system decides to do an update.

## Best Practices

Most functionality supported in a Widget is still supported when running as a Glance, such as accessing application storage and making web requests. However, developers should focus on making `WatchUi.GlanceView` quick to load and moving CPU-intensive work to a [Background service](https://developer.garmin.com/connect-iq/core-topics/background-services/).

---

<sup>1</sup> Music-capable wearables (these devices have more RAM available).
<sup>2</sup> Non-music wearables (these devices have more constrained RAM).
