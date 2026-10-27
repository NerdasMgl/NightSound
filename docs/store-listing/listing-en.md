# NightSound (夜莺) — Store listing (English)

## Basics

- App name: NightSound – Sleep & Snore Log (≤30 chars on Google Play)
- Package: com.yehang.app
- Version: 0.2.2 (versionCode 4)
- Category: Health & Fitness
- Keywords: sleep, snoring, sleep tracker, sleep report, breathing pauses, teeth grinding, sleep talking
- Content rating: Everyone (no ads, no purchases, no user-generated content)

## Short description (≤80 chars)

Put the phone by your pillow; get a plain-language report on sleep and snoring.

## Full description

NightSound is a quiet sleep log. Tap "Start monitoring" at bedtime, put the phone by your pillow, and in the morning you get a report you can actually read: how long you slept, how steadily, how much you snored, and whether there were suspected breathing pauses.

A REPORT FOR PEOPLE, NOT INSTRUMENTS
• A one-line verdict and a sleep score
• Four cards: time asleep, snoring, suspected breathing pauses, how steady the night was
• A whole-night timeline, one bar per 10 minutes, so you see when the snoring clustered; tap to hear the clip and check it yourself
• Compared with your recent nights
• A daytime sleepiness check-in, shown next to your night's snoring

SOUND RECOGNITION RUNS ON YOUR PHONE
• A built-in on-device sound model (Google YAMNet) tells snoring from breathing, coughing, talking and more
• Snoring must form a regular run to count; a sudden silence of 10+ seconds inside a snoring run is logged as a "suspected breathing pause"
• Teeth-grinding detection is experimental and can be turned off

PRIVACY
• No internet permission, no account, no ads, no analytics SDKs
• Audio is analysed on the phone; event clips can be turned off and are deleted automatically
• Delete everything with one tap

TIPS
• Place the phone 0.5–1 m from your head, screen down, plugged in
• Many phones kill background apps; follow the in-app guide so NightSound is set to "unrestricted"
• Falling asleep and waking are estimated from movement, screen use and sound. There is no heart rate and no REM staging.

IMPORTANT
NightSound is a daily logging tool, not a medical device, and cannot diagnose sleep apnea or any condition. A phone cannot hear airflow or measure blood oxygen; a "suspected breathing pause" is only a nudge. If you snore loudly, are very sleepy in the daytime, or wake up gasping, please see a doctor. Do not drive when you feel sleepy.

## What's new (0.2.0)

- New 5-step onboarding: where to put the phone, which permissions, how to keep it from being killed
- Report redesigned as a dashboard: verdict, four cards, whole-night timeline, comparison with recent nights
- Snoring and suspected breathing pauses now detected by an on-device sound model
- Timeline redrawn; coughing, talking and grinding marked; clips playable
- Dark, low-brightness bedside screen; hold 1.5 s to stop, to avoid accidental taps

## Google Play form hints

- Data safety: answer "No" to data collected and "No" to data shared. Audio is analysed on the device only and never leaves the phone; the app has no INTERNET permission. Re-check the wording against the form when you fill it in.
- App content → Health apps: declare it as a general wellness/tracking app, not a medical device.
- Foreground service declaration: type "microphone" — core function is overnight sound monitoring started by the user; prepare a short screen-recording showing the start button, the persistent notification, and the report.
- Permissions: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is restricted on Google Play. If review objects, remove it and send users to the system list via ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (no permission needed).
- Target API: Google Play requires API 36 for new apps and updates from Aug 31, 2026 (extension to Nov 1, 2026). The current build targets API 34 and must be retargeted and tested first.
