# Garmin SDK Documentation — Sources & Refresh Guide

> Last updated: 2026-02-28
> SDK Version at time of capture: Connect IQ 8.4.1 (System 8)

## How These Docs Were Obtained

The Garmin developer site (developer.garmin.com) has two types of pages:

- **API docs** — Server-rendered HTML. Can be fetched programmatically.
- **Core Topics & Reference Guides** — Client-side rendered (Gatsby/React). Must be manually saved from a browser.

### API docs (fetched automatically)

These were fetched via web scraping and converted to markdown:

| File | Source URL |
|------|-----------|
| `api-toybox-communications.md` | https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html |
| `api-toybox-watchui.md` | https://developer.garmin.com/connect-iq/api-docs/Toybox/WatchUi.html |
| `api-toybox-graphics.md` | https://developer.garmin.com/connect-iq/api-docs/Toybox/Graphics.html |
| `api-toybox-application.md` | https://developer.garmin.com/connect-iq/api-docs/Toybox/Application.html |
| `api-toybox-lang.md` | https://developer.garmin.com/connect-iq/api-docs/Toybox/Lang.html |
| `android-sdk-readme.md` | https://github.com/garmin/connectiq-android-sdk (README) |

### Core topics & guides (manually saved from browser)

These pages are JavaScript-rendered and cannot be fetched by automated tools. They were saved manually from the browser as `.txt` files, then parsed into markdown:

| File | Source URL |
|------|-----------|
| `communicating-with-mobile-apps.md` | https://developer.garmin.com/connect-iq/core-topics/communicating-with-mobile-apps/ |
| `mobile-sdk-for-android.md` | https://developer.garmin.com/connect-iq/core-topics/mobile-sdk-for-android/ |
| `glances.md` | https://developer.garmin.com/connect-iq/core-topics/glances/ |
| `monkey-c-reference.md` | https://developer.garmin.com/connect-iq/reference-guides/monkey-c-reference/ |

---

## How to Refresh

### Check for a new SDK version

Visit https://developer.garmin.com/connect-iq/sdk/ to see the latest SDK version. If a new major version has shipped, the APIs may have changed.

### Refresh API docs (automatic)

Ask Claude to re-fetch the API doc URLs listed above. The API docs pages are server-rendered and can be scraped directly.

### Refresh core topics & guides (manual)

1. Open each URL listed in the "manually saved" table above in a browser
2. Select all text on the page (Ctrl+A) and copy (Ctrl+C)
3. Paste into a `.txt` file in this folder (same name as the existing `.txt` file)
4. Ask Claude to re-parse the `.txt` files into `.md`

### Additional docs to consider fetching

If the project grows to need them, these would also be useful:

| Topic | URL |
|-------|-----|
| Layouts | https://developer.garmin.com/connect-iq/core-topics/layouts/ |
| Backgrounding | https://developer.garmin.com/connect-iq/core-topics/backgrounding/ |
| Persisting Data | https://developer.garmin.com/connect-iq/core-topics/persisting-data/ |
| Sensors | https://developer.garmin.com/connect-iq/core-topics/sensors/ |
| Manifest & Permissions | https://developer.garmin.com/connect-iq/core-topics/manifest-and-permissions/ |
| Your First App | https://developer.garmin.com/connect-iq/connect-iq-basics/your-first-app/ |
| Jungle Reference | https://developer.garmin.com/connect-iq/reference-guides/jungle-reference/ |
| Toybox.System API | https://developer.garmin.com/connect-iq/api-docs/Toybox/System.html |

### GitHub repos

| Repo | URL | Contents |
|------|-----|----------|
| Connect IQ Android SDK | https://github.com/garmin/connectiq-android-sdk | Companion app SDK + Comm sample |
| Connect IQ Example Apps | https://github.com/garmin/connectiq-apps | Official example apps (Widgets, etc.) |
| Connect IQ iOS SDK | https://github.com/garmin/connectiq-companion-app-sdk-ios | iOS companion SDK |
