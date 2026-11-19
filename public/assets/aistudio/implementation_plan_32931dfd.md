# Implementation Plan: OmniCall Polish, Incoming Latency Fix, In-Call & Recents Overhaul

## Goal
Resolve seven user-reported functional and UX issues in OmniCall while keeping all existing capabilities intact (Hi-Fi Call Recording, Dual-SIM & SIP calling, Blocklist, Contacts VCF Import/Export, Themes, Swipe Incoming UI, and Floating Mini Call Window).

## Confirmed User Preferences
- **Recents Grouping:** Merge consecutive calls with the same contact using a count badge (e.g., `Ammu (5)`), under `Today`, `Yesterday`, and `Older` date headers.
- **In-Call Controls Layout:** Frame the bottom action tiles inside a rounded dark control dock with a raised, comfortably reachable End Call pill button.
- **SIP Unanswered / Declined:** Terminate cleanly with a brief neutral status message (`No answer`, `Call declined`, `User busy`)—no red error banner and zero automatic re-dial retries.
- **Settings Phone Info:** Attempt to open Android's hidden `RadioInfo` (`Phone info`) screen first, falling back gracefully to Mobile Network Settings if restricted by device OEM firmware.

## System Flow & Architecture Overview

```
[Incoming SIM Call]
   ├── CallScreening (In-memory block cache + <=150ms fast path -> instant allow)
   └── InCallService.onCallAdded
         ├── Instant RINGING state update (no blocking SIM/Contact IPC)
         ├── Immediate Incoming Call UI launch + Single-line Call Notification
         └── Async Contact photo/name + SIM slot enrichment

[Outgoing SIP Call]
   ├── 180/183 Ringing -> Start local ringback, clear dial-format fallbacks
   ├── 486 Busy / 603 Decline / 480 No Answer -> Neutral status (~1s) -> Clean close
   └── 404 / 484 Format Ambiguity (pre-ring only) -> Safe fallback queue retry
```

## Planned Workstreams

### 1. Multi-Entry Dialer Keypad Suggestions
- **Dialer ViewModel:** Remove the premature `.take(4)` slice on raw call logs before suggestion deduplication. Instead, scan recent call history sequentially and deduplicate by normalized phone number/SIP handle until up to 5 distinct recent callers are collected (and up to 6 total when filtering by typed digits/name).
- **Dialer Screen:** Ensure the scrollable suggestion list above the keypad cleanly displays multiple distinct contact/recent cards with avatars, call direction icons, and line badges.

### 2. Eliminate 6-Second Background Incoming Cellular Call Delay
- **Call Screening Service:** Pre-warm the blocklist key cache at app startup, check the in-memory fast cache first in `onScreenCall`, and cap any database fallback lookup with a tight timeout (~150ms) so Android Telecom never stalls for its 5-second screening timeout before ringing the dialer UI.
- **Application Startup:** Offload Telecom self-managed account registration, notification channel setup, initial SIM slot scan, and blocklist cache warmup from blocking cold-start critical paths.
- **In-Call Service & Call Coordinator:** When an incoming cellular call arrives in `onCallAdded`, populate a fast non-blocking `RINGING_INCOMING` call state immediately using cached SIM info, launch the full-screen Incoming Call window without waiting on `SubscriptionManager`/ContentResolver IPC or synchronous bitmap decoding, and resolve contact photos/names asynchronously right after. Also ensure native cellular incoming notifications do not contend with Telecom's system ringtone audio stream.

### 3. Google Phone–Inspired Rounded In-Call Control Dock
- **In-Call Screen:** Wrap the 6 action tiles (`Mute`, `Keypad`, `Speaker / Audio`, `Record`, `Hold`, `Tap Ready`) and the `End Call` button inside an elevated rounded bottom dock card with generous internal spacing and clean clearance above the system gesture/navigation bar, raising the red End Call pill so it is effortless to tap one-handed.
- Keep the expanded in-call DTMF keypad panel and swipe-to-answer incoming pill aligned cleanly within safe drawing insets.

### 4. Concise Single-Line Incoming Mini Notification
- **Call Notification Manager & Active Call Coordinator:** Strip the mobile carrier name (such as `Grameenphone`) from incoming notification text and subtext. Format the heads-up notification subtitle cleanly on one concise line as `Incoming Via SIM 1, +8801783103515` (or `Incoming Via SIM 2, ...` / `Incoming Via SIP, ...`) while keeping caller name, avatar, Answer, and Decline buttons crisp.

### 5. Consecutive Call Grouping & Date Sections in Recents
- **Recents ViewModel:**
  - Categorize filtered call logs into chronological `Today`, `Yesterday`, and `Older` date buckets.
  - Merge consecutive call entries that share the same normalized contact handle within the same date bucket, exposing the newest entry metadata alongside `callCount` and the full list of grouped log IDs.
  - Update multi-select deletion and swipe-to-delete to support deleting grouped streaks cleanly while still allowing single-entry inspection/deletion inside the history detail sheet.
- **Recents Screen:**
  - Render clean `Today`, `Yesterday`, and `Older` section headers.
  - Display the repeat count badge beside the contact name (e.g., `Ammu (5)`), retaining avatar, call direction indicator, line pill (`SIM 1`/`SIM 2`/`SIP`), duration, recording indicator, and tap-to-open unified Call History modal sheet.

### 6. SIP Unanswered (`480`) & Declined/Busy Retry Loop Fix
- **Active Call Coordinator & SIP Client Engine:**
  - Restrict automatic pre-ring number-format fallbacks strictly to address ambiguity codes (`404`, `484`). Never retry dialing when a callee rejects (`603 Decline`), is busy (`486 Busy Here`), times out (`480` / `408`), or cancels (`487`). Clear any queued fallback numbers as soon as `180/183` ringing or terminal rejection is received.
  - Map `480`/`408` to `"No answer"`, `486`/`600` to `"User busy"`, and `603` to `"Call declined"`. Treat these as normal call termination states: briefly display the neutral status on the call screen (~1 second) before closing cleanly, without emitting to the Dialer red error banner.

### 7. Settings "Phone info" (`RadioInfo`) Entry
- **Settings Screen & Launcher Utility:** Add a **Phone info** action card inside Settings (near Telephony & SIM Slots) that launches Android's hidden RadioInfo testing screen (`com.android.phone.settings.RadioInfo` / `com.android.settings.RadioInfo` / `TestingSettingsActivity`) for LTE/5G/NR band & preferred network customization. If blocked by OEM restrictions (`SecurityException` / `ActivityNotFoundException`), automatically fall back to Android Mobile Network Settings and show a concise hint for the `*#*#4636#*#*` dial code.

## Verification Plan
- Add unit tests covering:
  - Consecutive Recents call grouping with date buckets (`Today`, `Yesterday`, `Older`) and count badges.
  - Keypad suggestions returning multiple distinct recent callers even when consecutive calls are to the same contact.
  - SIP call failure classification ensuring `486`/`603`/`480`/`408` never trigger fallback re-dials and never emit red Dialer error events.
  - Incoming notification subtitle formatting excluding carrier names (`Incoming Via SIM 1, <number>`).
- Run full project compilation (`compile_applet`) and local JVM unit/Robolectric tests (`gradle :app:testDebugUnitTest --console=plain`) to confirm zero regressions across all existing features.
