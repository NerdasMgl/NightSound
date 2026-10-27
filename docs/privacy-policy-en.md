# NightSound (夜莺) Privacy Policy

Last updated: October 28, 2026 (applies to version 0.2.0 and later)

Developer: [fill in before publishing: name]
Contact: [fill in before publishing: email]

## In one sentence

NightSound does not collect or upload any of your information. The app does not request the INTERNET permission; all sound analysis and storage happen on your own phone. No account, no ads, no analytics, no third-party SDKs.

## What the app processes on your phone

| Information | When and why | How it is stored |
| --- | --- | --- |
| Microphone audio | Only after you tap "Start monitoring"; used to recognise snoring, suspected breathing pauses, coughing, talking and teeth grinding. | Analysed live in memory. Full-night raw audio is **not** saved. Only when an event is detected, a short clip around it is saved so you can replay and check it in the report. You can turn this off in Settings; clips are kept 7 days by default (3 / 7 / 14 / 30 selectable) and then deleted automatically. |
| Accelerometer, light sensor, screen on/off | During monitoring, to estimate when you fall asleep and wake up. These sensors need no extra permission. | Only per-minute summary values are stored; raw sensor data is not. |
| What you enter | Bedtime habit tags, daytime sleepiness check-ins, sleep goal. | Stored on the phone, used only for your own reports. |
| Statistics | Per-night sleep duration, snoring share, times of events, etc. | Stored on the phone. |
| Diagnostic log | App run/error records to help troubleshooting. | Stored on the phone only. It leaves the phone only if you tap "Share diagnostics" under Me > Advanced and choose where to send it. |

All of this lives in the app's private storage, which other apps cannot read. System backup is disabled (allowBackup=false), so the data is not backed up to the cloud either.

## What the app does not do

- No networking: the app has no internet permission; nothing is ever sent from the app to any server.
- No account; no name, phone number, location, contacts or device identifiers are collected.
- No ads, no analytics, no third-party SDKs.
- Your data is never provided to any third party.

## What you can do at any time

- Delete any single night's records from its report;
- Clear all audio clips, or turn off "Save event clips" so no audio is saved from then on, under Me > Recording & privacy;
- Delete all data under Me > Data;
- Export all records as CSV to Downloads/夜莺 (only when you tap export);
- Uninstall the app, which deletes all its data.

## Permissions and why

| Permission | Purpose |
| --- | --- |
| Microphone (RECORD_AUDIO) | Recognise snoring and other sounds during monitoring. Required for monitoring. |
| Foreground service / microphone-type foreground service | Keep the overnight monitoring running with the screen off, with a "Monitoring" notification. |
| Notifications (POST_NOTIFICATIONS) | Show that notification. If denied, monitoring still works without it. |
| Wake lock (WAKE_LOCK) | Keep the processor running while monitoring so recording is not interrupted. Released when monitoring ends; monitoring auto-stops after 15 hours. |
| Ignore battery optimisations (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) | Lets you exempt the app from battery restrictions so the system does not kill an overnight session. The system dialog appears only if you tap "Go to settings", and you decide. |

## Sound recognition model

NightSound uses Google's open-source YAMNet sound classifier (Apache License 2.0). The model ships inside the app and runs entirely on your phone; it is never downloaded or updated over the network.

## Important notice

NightSound is a daily logging tool, not a medical device. Snoring share and "suspected breathing pauses" are estimates from sound and movement and cannot diagnose sleep apnea or any other condition, nor replace medical advice. If you snore loudly, feel very sleepy in the daytime, or wake up gasping, please see a sleep clinic, respiratory or ENT doctor. Do not drive when you feel sleepy.

## Minors

The app has no accounts and collects no personal information. Parents or guardians may wish to review this policy before a minor uses it.

## Changes

If a future version changes how data is handled (for example by adding networking), this policy will be updated first, and the app will tell you clearly and ask for your consent before the change takes effect.

## Contact

Questions about this policy: [fill in before publishing: email].
