# Toybox.Graphics API Reference

> Source: https://developer.garmin.com/connect-iq/api-docs/Toybox/Graphics.html
> SDK Version: Connect IQ 8.4.1 (System 8) — February 2026

## Module Overview

The Graphics module provides drawing functionality for Connect IQ applications.

- **API Level:** 1.0.0
- **Supported App Types:** Watch Apps, Watch Faces, Widgets, Data Fields, Glances, Audio Content Providers, Background (5.1.0+)

---

## Core Classes

| Class | Purpose |
|-------|---------|
| **Dc** | Device context for drawing operations |
| **BufferedBitmap** | Off-screen rendering surface |
| **BitmapReference** | Reference to bitmap resources |
| **VectorFont** | Scalable font support |
| **FontReference** | Reference to font resources |
| **AffineTransform** | 2D transformation matrix |
| **BoundingBox** | Rectangle boundary definition |

---

## Font Constants

### Standard Fonts
| Constant | Value | Description |
|----------|-------|-------------|
| `FONT_XTINY` | 0 | Extra tiny |
| `FONT_TINY` | 1 | Tiny |
| `FONT_SMALL` | 2 | Small |
| `FONT_MEDIUM` | 3 | Medium |
| `FONT_LARGE` | 4 | Large |
| `FONT_GLANCE` | 18 | Glance text |
| `FONT_GLANCE_NUMBER` | 19 | Glance numbers |

### Number-Only Fonts
| Constant | Value |
|----------|-------|
| `FONT_NUMBER_MILD` | 5 |
| `FONT_NUMBER_MEDIUM` | 6 |
| `FONT_NUMBER_HOT` | 7 |
| `FONT_NUMBER_THAI_HOT` | 8 |

---

## Color Constants

| Constant | Value | Color |
|----------|-------|-------|
| `COLOR_WHITE` | 0xFFFFFF | White |
| `COLOR_BLACK` | 0x000000 | Black |
| `COLOR_RED` | 0xFF0000 | Red |
| `COLOR_DK_RED` | 0xAA0000 | Dark Red |
| `COLOR_GREEN` | 0x00FF00 | Green |
| `COLOR_DK_GREEN` | 0x00AA00 | Dark Green |
| `COLOR_BLUE` | 0x00AAFF | Blue |
| `COLOR_DK_BLUE` | 0x0000FF | Dark Blue |
| `COLOR_YELLOW` | 0xFFAA00 | Yellow |
| `COLOR_ORANGE` | 0xFF5500 | Orange |
| `COLOR_PURPLE` | 0xAA00FF | Purple |
| `COLOR_PINK` | 0xFF00FF | Pink |
| `COLOR_LT_GRAY` | 0xAAAAAA | Light Gray |
| `COLOR_DK_GRAY` | 0x555555 | Dark Gray |
| `COLOR_TRANSPARENT` | -1 | Transparent |

---

## Text Justification

| Constant | Value | Description |
|----------|-------|-------------|
| `TEXT_JUSTIFY_LEFT` | 2 | Left-aligned |
| `TEXT_JUSTIFY_CENTER` | 1 | Center-aligned |
| `TEXT_JUSTIFY_RIGHT` | 0 | Right-aligned |
| `TEXT_JUSTIFY_VCENTER` | 4 | Vertically centered |

---

## Dc (Device Context) Class

> "You should never directly instantiate a Dc object, or attempt to render to the screen outside of an onUpdate call."

### Color & Style

#### `setColor(foreground, background)`
```monkeyc
setColor(foreground as ColorType, background as ColorType) as Void
```
Sets foreground and background colors. Accepts `Graphics.COLOR_*` constants or 24-bit RGB integers (`0xRRGGBB`).

#### `setPenWidth(width)`
```monkeyc
setPenWidth(width as Numeric) as Void
```
Sets line width in pixels for drawn shapes.

#### `setAntiAlias(enabled)`
```monkeyc
setAntiAlias(enabled as Boolean) as Void
```
Enables/disables anti-aliased rendering.

#### `setBlendMode(mode)`
```monkeyc
setBlendMode(mode as BlendMode) as Void
```
Blend modes: `BLEND_MODE_SOURCE_OVER` (0), `BLEND_MODE_SOURCE` (1), `BLEND_MODE_MULTIPLY` (2), `BLEND_MODE_ADDITIVE` (3).

### Display Management

#### `clear()`
```monkeyc
clear() as Void
```
Erases the screen using the background color.

#### `getWidth()`
```monkeyc
getWidth() as Number
```
Returns width of display region in pixels.

#### `getHeight()`
```monkeyc
getHeight() as Number
```
Returns height of display region in pixels.

### Text Drawing

#### `drawText(x, y, font, text, justification)`
```monkeyc
drawText(x as Numeric, y as Numeric, font as FontType,
         text as Object or Null, justification as TextJustification or Number) as Void
```
Renders text at specified coordinates.

### Text Measurement

#### `getTextDimensions(text, font)`
```monkeyc
getTextDimensions(text as String, font as FontType) as [Number, Number]
```
Returns `[width, height]` in pixels.

#### `getTextWidthInPixels(text, font)`
```monkeyc
getTextWidthInPixels(text as String, font as FontType) as Number
```

#### `getFontHeight(font)`
```monkeyc
getFontHeight(font as FontType) as Number
```

### Shape Drawing

#### `drawLine(x1, y1, x2, y2)`
```monkeyc
drawLine(x1 as Numeric, y1 as Numeric, x2 as Numeric, y2 as Numeric) as Void
```

#### `drawRectangle(x, y, width, height)` / `fillRectangle(x, y, width, height)`
```monkeyc
drawRectangle(x as Numeric, y as Numeric, width as Numeric, height as Numeric) as Void
fillRectangle(x as Numeric, y as Numeric, width as Numeric, height as Numeric) as Void
```

#### `drawRoundedRectangle(x, y, w, h, radius)` / `fillRoundedRectangle(x, y, w, h, radius)`
```monkeyc
drawRoundedRectangle(x as Numeric, y as Numeric, width as Numeric,
                     height as Numeric, radius as Numeric) as Void
```

#### `drawCircle(x, y, radius)` / `fillCircle(x, y, radius)`
```monkeyc
drawCircle(x as Numeric, y as Numeric, radius as Numeric) as Void
fillCircle(x as Numeric, y as Numeric, radius as Numeric) as Void
```

#### `drawEllipse(x, y, a, b)` / `fillEllipse(x, y, a, b)`
```monkeyc
drawEllipse(x as Numeric, y as Numeric, a as Numeric, b as Numeric) as Void
```
`a` = x-axis radius, `b` = y-axis radius.

#### `drawArc(x, y, r, attr, degreeStart, degreeEnd)`
```monkeyc
drawArc(x as Numeric, y as Numeric, r as Numeric, attr as ArcDirection,
        degreeStart as Numeric, degreeEnd as Numeric) as Void
```
Angle reference: 0° = 3 o'clock, 90° = 12 o'clock, 180° = 9 o'clock, 270° = 6 o'clock.

#### `drawPoint(x, y)`
```monkeyc
drawPoint(x as Numeric, y as Numeric) as Void
```

#### `fillPolygon(pts)`
```monkeyc
fillPolygon(pts as Array<Point2D>) as Void
```
64-point limit per array.

### Bitmap Operations

#### `drawBitmap(x, y, bitmap)`
```monkeyc
drawBitmap(x as Numeric, y as Numeric, bitmap as BitmapType) as Void
```

#### `drawScaledBitmap(x, y, width, height, bitmap)`
```monkeyc
drawScaledBitmap(x as Numeric, y as Numeric, width as Numeric,
                 height as Numeric, bitmap as BitmapType) as Void
```

### Clipping

#### `setClip(x, y, width, height)`
Restricts drawable area to a rectangular region.

#### `clearClip()`
Resets drawable area to full Dc extent.

---

## Type Aliases

| Alias | Definition |
|-------|-----------|
| `ColorType` | `Number` or `ColorValue` constant |
| `FontType` | `FontResource \| FontDefinition \| FontReference \| VectorFont` |
| `BitmapType` | `BitmapResource \| BufferedBitmap \| BitmapReference \| BufferedBitmapReference` |
| `Point2D` | `[x as Numeric, y as Numeric]` |
