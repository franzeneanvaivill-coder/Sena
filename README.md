# Sena (Android, Stage 1)

## Build the APK
**Computer:** open this folder in Android Studio, let it sync, then Build > Build APK(s).
**Cloud (no computer):** put these files in a new GitHub repository (keep the folder paths; the .github folder matters),
open the Actions tab, run "Build APK", and download the Sena-apk artifact. The file is app-debug.apk.

## First run on the phone
1. Allow "install unknown apps" for your browser or file manager, then install the APK.
2. Open Sena, paste your free Gemini key (aistudio.google.com/apikey), set your language code, tap Start Sena.
3. Allow the microphone and notifications, and choose "Don't optimize" / "Allow" for battery when asked.
4. Say "Sena, what time is it?" or "Sena, set an alarm for 6:30 am".

## Known limits in this version
- Listening uses Android's speech service, which needs internet and may beep between phrases.
- She only answers when she hears her name (or within 20 seconds of speaking). Real voice recognition of *you* is Stage 2.
- Alarms are lost if the phone restarts. Music is not included yet.
- Some phone brands kill background apps; if she stops listening, look for the brand's battery or auto-start settings.
