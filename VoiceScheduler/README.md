# Voice Scheduler 2.0

Schedule WhatsApp voice notes for saved contact profiles, with an alarm + manual approval at send time.

## How it works
1. Create a profile (name, number, gallery photo, optional personal alarm sound).
2. Open the profile, record or pick audio (always converted to OGG/Opus, WhatsApp's voice-note format), pick date/time.
3. `AlarmManager.setAlarmClock` fires at the exact time (survives Doze, re-armed after reboot/app update).
4. The alarm rings (system alarm / your MP3 / vibrate) and a full-screen **APPROVE & SEND** screen appears, over the lock screen if allowed. If you unlock later, it appears right after unlock.
5. Tap APPROVE (the phone asks for PIN/fingerprint if locked). The original sending logic then shares the file into the exact chat and the accessibility service taps Send.

## Duplicate-send fix (root cause)
Old code could open WhatsApp for the same note many times: the full-screen notification's PendingIntent could be replayed, the app re-sent overdue tasks on every open and on boot, and a failed attempt re-shared the file a second time. Now each task moves one way:
`PENDING -> AWAITING -> SENDING -> SENT/FAILED/MISSED`, every step is an atomic, persisted compare-and-set in `Store`, and WhatsApp can be launched for a task at most once (`Store.markLaunched`). Alarms, notifications, boot, app open and retries can never start a second send.

## Consistent voice-note format
The old code silently fell back to copying MP3/M4A as-is when conversion failed, which arrived as a plain audio attachment. That fallback is removed: audio is converted (twice if needed) or rejected with a message. Already-Opus files are copied untouched.

## Build
GitHub Actions builds `app-debug.apk` on every push (Actions tab -> Artifacts). The existing workflow works unchanged.
Local: `gradle assembleDebug` (Gradle 8.7, JDK 17).

## Notes
- Needs accessibility service, overlay, notifications, full-screen alerts and unrestricted battery (Settings screen shows each).
- No official WhatsApp API for scheduling; automation depends on WhatsApp's UI.
- Vivo/Oppo/Xiaomi: also enable Autostart.
