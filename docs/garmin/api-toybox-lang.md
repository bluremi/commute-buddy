# Toybox.Lang API Reference

> Source: https://developer.garmin.com/connect-iq/api-docs/Toybox/Lang.html
> SDK Version: Connect IQ 8.4.1 (System 8) — February 2026

## Module Overview

The Lang module provides fundamental Monkey C language types and string formatting. API Level 1.0.0.

---

## Core Classes

### Primary Types
- **Object** — Base class for all Monkey C objects
- **Array** — Ordered collection
- **Dictionary** — Key-value collection
- **String** — Text
- **Number** — 32-bit signed integer
- **Float** — 32-bit floating point
- **Long** — 64-bit signed integer
- **Double** — 64-bit floating point
- **Boolean** — true/false
- **Char** — Single character
- **Symbol** — Interned identifier (`:symbolName`)
- **Method** — Function reference
- **WeakReference** — Non-preventing garbage collection reference
- **ByteArray** — Raw byte buffer
- **ResourceId** — Resource identifier

### Exception Types
- **Exception** — Base exception
- **InvalidOptionsException**
- **InvalidValueException**
- **OperationNotAllowedException**
- **SerializationException**
- **StorageFullException**
- **UnexpectedTypeException**
- **ValueOutOfBoundsException**
- **SymbolNotAllowedException**

---

## Type Definitions

| Typedef | Definition |
|---------|-----------|
| **Numeric** | `Number \| Float \| Long \| Double` |
| **Integer** | `Number \| Long` |
| **Decimal** | `Float \| Double` |

---

## Key Methods

### `Lang.format(format, parameters)`
```monkeyc
Lang.format(format as String, parameters as Array) as String
```
Creates formatted strings using `$1$`, `$2$` substitution placeholders.

```monkeyc
var result = Lang.format("Meeting at $1$:$2$", [2, 30]);
// => "Meeting at 2:30"
```
