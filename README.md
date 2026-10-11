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

## Stage 3 update: a natural, mature voice
- Sena now speaks with Gemini's speech voice **Gacrux** (labelled "Mature"), not the phone's robotic voice.
  If the voice service fails or is rate-limited, she falls back to the phone's own voice automatically.
- To change the voice, open `app/src/main/java/com/sena/app/Gemini.kt` and change the word after `const val VOICE =`.
  Other good options: Sulafat (warm), Vindemiatrix (gentle), Schedar (even), Achernar (soft).
  Listen to them first at aistudio.google.com/generate-speech.
- More human conversation: she keeps listening for 45 seconds after each reply (no need to repeat "Sena" while you
  are chatting), remembers the last 10 exchanges, and talks in short, natural phone-call sentences. Long replies are
  spoken sentence by sentence so she starts talking sooner.
- The voice service counts against your Gemini quota. If her voice suddenly goes robotic, the Activity log will show a "Voice error" line.
- Still not possible: interrupting her while she talks.

## Stage 4 update: faster, sharper, more like JARVIS
- **Brain switch (main screen):** "Smarter brain" uses Gemini 3.5 Flash, which understands Bisaya much better than the
  lite model. Turn it off for the fastest (but less accurate) replies.
- **Look things up online:** she can search Google for news, weather, scores and other current facts.
- **Speed:** the model's "thinking" time is cut to the minimum, the connection to Google is reused between questions,
  and she decides you have finished speaking about 140 ms sooner.
- **Bisaya:** the instructions now teach her to write down exactly what you said in Bisaya, answer in casual Bisaya,
  and ask you to repeat when she is not sure instead of guessing. The activity log shows what she thought you said
  ("You: ...") so you can see where she misheard.

## Stage 5 update: instant mode (Gemini Live)
- New switch on the main screen: **Instant mode**. Sena now keeps a live connection to Google. When you finish speaking and
  your voice passes the check, your sentence is sent and her voice starts streaming back while she is still thinking:
  no separate "make her voice" request, no waiting for the whole answer.
- Alarms, timers and "what time is it" now work through live tools; she can still search Google for current facts.
- The activity log shows how fast it was: `Reply started 900 ms after you finished.`
- If the live connection fails for any reason, she falls back to the classic method automatically and the log says why.
- Limits: she cannot be interrupted yet (while she talks she ignores new sounds, which also keeps her from hearing herself).
  The live service is a preview and its usage cost is higher than the classic mode; check Google's pricing page.

## Stage 6 update: "Sena, activate"
- Sena starts **asleep**. She ignores everything until she hears **"Sena, activate"** (or "Partner, activate") in your trained voice.
- Once awake she answers only when she hears **"Sena"** or **"Partner"** in that sentence, every time. No follow-ups without her name.
- **"Sena, deactivate"** or **"Sena, go to sleep"** puts her back to sleep.
- The main screen shows whether she is asleep or awake. A switch lets you turn "start asleep" off.
- Keep **Only obey my voice** on, otherwise anyone can wake her.
- While asleep she still listens, because she has to catch "activate". Sentences in your voice are still sent to Google for that
  (the phone cannot recognize a wake word by itself). Stop Sena from the notification if you want the mic fully off.
