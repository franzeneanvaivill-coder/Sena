# Sena (Android, Stage 2: she knows your voice)

## Build the APK
**Computer:** open this folder in Android Studio, let it sync, then Build > Build APK(s).
**Cloud (no computer):** put these files in a GitHub repository (keep the folder paths; the .github folder matters),
open the Actions tab, run "Build APK", and download the Sena-apk artifact. The file is app-debug.apk.

## First run on the phone
1. Allow "install unknown apps" for your browser or file manager, then install the APK.
2. Open Sena, paste your free Gemini key (aistudio.google.com/apikey), set your language code.
3. Tap **Train my voice** in a quiet room and read the 8 phrases it shows. Voice lock turns on by itself.
4. Tap **Start Sena**. Allow the microphone and notifications, and choose "Don't optimize" for battery when asked.
5. Say "Sena, what time is it?" or "Sena, set an alarm for 6:30 am".

## What changed in Stage 2
- Sena now records the microphone herself (no Android speech service, so no beeps). Each phrase is checked against your
  voiceprint **on the phone**. Only audio that matches you is sent to Gemini, which transcribes it, decides if it was
  meant for Sena, and answers.
- With voice lock on, a ringing alarm or someone else talking does not trigger her. "Sena, turn off the alarm" works by voice.
- The activity log shows every voice check, e.g. `Voice check 1.12 (limit 2.18): you`. If she ignores you, move the
  strictness slider right; if others can wake her, move it left. Your own voice should score around 1.

## Honest limits
- The voiceprint is a lightweight statistical one (MFCC + pitch), not a neural model. It separates clearly different voices
  well, but a similar-sounding voice can pass, so treat it as a convenience lock, not security. A neural speaker model
  is the natural Stage 3 upgrade.
- This version was written from the Stage 1 APK, not compiled or run here. Expect to fix a small build or runtime
  issue on first try; send me the error text.
- Anything you say in your own voice goes to Gemini, even if it was not meant for Sena (she then stays silent).
  With voice lock off, everyone's speech is sent.
- She cannot be interrupted while thinking or speaking.
- Alarms are lost if the phone restarts. Music is not included yet.
- Some phone brands kill background apps; if she stops listening, look for the brand's battery or auto-start settings.
