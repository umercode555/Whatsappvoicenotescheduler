# Voice Scheduler

Schedule WhatsApp voice notes. UI mirrors WhatsApp's "Schedule message" sheet.

## How it works
1. Record (OGG/Opus) or pick audio (auto-converted to Opus, WhatsApp's voice format).
2. Pick contact, open sheet, choose date/time, tap green button.
3. `AlarmManager.setAlarmClock` fires at exact time (survives Doze, reboot-rescheduled).
4. Foreground service wakes screen, dismisses swipe-lock, shares file into the exact chat via WhatsApp `jid` intent.
5. Accessibility service taps WhatsApp's Send button, then goes Home.

## Build
GitHub Actions builds `app-debug.apk` on every push (Actions tab → Artifacts).
Local: `gradle assembleDebug` (Gradle 8.7, JDK 17).

## Limits (unavoidable)
- No official WhatsApp API for voice scheduling. Automation depends on WhatsApp's UI; big WhatsApp redesigns may need a patch to the Send-button finder.
- Phone must be on and not PIN/pattern/fingerprint locked at send time.
- Number must be on WhatsApp.
- File arrives in Opus format like a voice note; WhatsApp decides final rendering (waveform vs audio file).
- OEM battery killers (Vivo/Oppo/Xiaomi): enable Autostart + Unrestricted battery.
