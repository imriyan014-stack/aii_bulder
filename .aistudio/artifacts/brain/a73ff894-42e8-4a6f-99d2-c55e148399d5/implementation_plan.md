# ARCHER AI — Gemini 2.0 Flash Live (`BidiGenerateContent`), পার্মানেন্ট স্ক্রিন শেয়ার ও রিয়েল হিউম্যান ভয়েস আপগ্রেড প্ল্যান

## ১. সারসংক্ষেপ (Overview)
আপনার নির্দেশনা অনুযায়ী **ARCHER AI**-কে একটি পূর্ণাঙ্গ **Personal Mobile Agent**-এ রূপান্তর করার জন্য নিচের ৪টি মূল আপগ্রেড যুক্ত করা হচ্ছে:
1. **Gemini Multimodal Live API (`BidiGenerateContent` over WebSockets with `gemini-2.0-flash-exp`)**:
   - ওয়েবসকেটের মাধ্যমে সরাসরি `models/gemini-2.0-flash-exp` মডেলের সাথে রিয়েল-টাইম বাই-ডিরেকশনাল (`BidiGenerateContent`) অডিও, ক্যামেরা ও স্ক্রিন ফ্রেম স্ট্রিমিং।
2. **Real-Time Audio Streaming ও Instant Barge-In (কথা বললেই সাথে সাথে চুপ হয়ে শোনা)**:
   - মাইক্রোফোন থেকে `AudioRecord` (16kHz PCM) ও `SpeechRecognizer`-এর মাধ্যমে রিয়েল-টাইম ভয়েস স্ট্রিমিং এবং `AudioTrack` (`MODE_STREAM`, 24kHz PCM) দিয়ে নিরবচ্ছিন্ন লাইভ অডিও প্লেব্যাক।
   - **Instant Barge-In**: এজেন্ট কথা বলার মাঝখানে আপনি কথা বলা শুরু করলেই (Local VAD + SpeechBeginning + WebSocket `interrupted` ইভেন্ট) এজেন্ট মুহূর্তের মধ্যে (`<20ms`) নিজের কথা থামিয়ে আপনার নতুন কথা শুনবে এবং উত্তর দেবে।
3. **Permanent Screen Sharing (একবার পারমিশন দিলেই আজীবন/পার্মানেন্ট স্ক্রিন ভিশন)**:
   - স্ক্রিন শেয়ার বাটনে চাপ দিলে বা **"স্ক্রিনে দেখ"** বললে প্রথমবার সিস্টেমের `MediaProjectionManager` স্ক্রিন ক্যাপচার পারমিশন পপআপ ও অ্যাক্সেসিবিলিটি পারমিশন চাইবে।
   - একবার পারমিশন দেওয়ার পর `PermanentScreenCaptureService` (`MediaProjection` + `VirtualDisplay` + `ImageReader`) এবং `CubeAccessibilityService.takeScreenshot()` টোকেনটি সংরক্ষিত রাখবে। ফলে পরের বার থেকে আপনি শুধু **"স্ক্রিনে দেখ"** বললেই কোনো পারমিশন পপআপ ছাড়াই সাথে সাথে স্ক্রিন শেয়ার চালু হয়ে পুরো স্ক্রিন দেখবে।
4. **ডানে-বামে-উপরে-নিচে সম্পূর্ণ অটোমেটিক স্ক্রিন কন্ট্রোল ও মানুষের মতো রিয়েল ভয়েস (Natural Human Voice)**:
   - স্ক্রিন দেখে নিজে থেকেই যেকোনো বাটনে ক্লিক, উপরে-নিচে-ডানে-বামে (`UP`, `DOWN`, `LEFT`, `RIGHT`) স্ক্রল/সোয়াইপ, কল করা, মেসেজ পাঠানো এবং নোটিফিকেশন বারের সব সেটিংস অন-অফ করবে।
   - ভয়েস সিস্টেমে **Natural Human Voice (`NATURAL_HUMAN`)** প্রোফাইল ও কনভার্সেশনাল প্রোসোডি যুক্ত করা হবে, যাতে এজেন্টের কথা রোবট বা লেখা পড়ার মতো না শুনিয়ে রক্ত-মাংসের মানুষের সাথে সরাসরি কথা বলার মতো স্বাভাবিক শোনায়।

---

## ২. টেকনিক্যাল আর্কিটেকচার ও পরিবর্তনসমূহ (Technical Architecture)

### A. Gemini Multimodal Live API (`gemini-2.0-flash-exp` WebSocket + Barge-In)
- **ফাইল**: `app/src/main/java/com/example/service/MultimodalLiveService.kt`
  - **মডেল আপডেট**: ওয়েবসকেট Bidi মডেল হিসেবে `models/gemini-2.0-flash-exp` সেট করা হবে (`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`)।
  - **Real-Time 16kHz PCM Audio Input Streaming**:
    - `AudioRecord` (16kHz, 16-bit Mono PCM) ব্যবহার করে মাইক্রোফোন থেকে রিয়েল-টাইম অডিও চাঙ্ক (`audio/pcm;rate=16000`) ওয়েবসকেটের `realtimeInput.mediaChunks`-এ স্ট্রিম করা হবে।
  - **Continuous 24kHz PCM AudioTrack Output & Instant Barge-In**:
    - ওয়েবসকেট থেকে আসা `serverContent.modelTurn.parts[].inlineData` (`audio/pcm`) চাঙ্কগুলো একটি স্ট্রিমিং `AudioTrack`-এ কিউ করে সাথে সাথে বাজানো হবে।
    - সার্ভার থেকে `serverContent.interrupted == true` আসলে অথবা মাইক্রোফোনে ইউজারের কণ্ঠস্বর (VAD RMS threshold / `onBeginningOfSpeech`) শনাক্ত হলেই সাথে সাথে `stopAndFlushLiveAudioTrack()` ও `ttsModule.stopSpeaking()` কল হয়ে এজেন্ট থেমে যাবে এবং আপনার নতুন কথা শুনবে।

### B. পার্মানেন্ট স্ক্রিন শেয়ারিং ইঞ্জিন (One-Time Permission Screen Capture)
- **নতুন ফাইল**: `app/src/main/java/com/example/service/PermanentScreenCaptureService.kt`
  - `MediaProjectionManager` থেকে প্রথমবার পাওয়া `resultCode` ও `data: Intent` গ্রহণ করে একটি ফোরগ্রাউন্ড সার্ভিস (`foregroundServiceType="mediaProjection"`) ও `VirtualDisplay` + `ImageReader` চালু রাখবে।
  - `ScreenCaptureSessionBus`-এর মাধ্যমে যেকোনো সময় (`getLatestScreenBitmap()`) রিয়েল-টাইম স্ক্রিন বিটম্যাপ সরবরাহ করবে।
  - এর পাশাপাশি `CubeAccessibilityService`-এর `takeScreenshot(Display.DEFAULT_DISPLAY, ...)` ব্যাকআপ হিসেবে সংযুক্ত থাকবে, যাতে অ্যাপ বন্ধ করে পুনরায় ওপেন করলেও কখনো দ্বিতীয়বার পারমিশন না চায়।
- **ফাইল**: `app/src/main/AndroidManifest.xml`
  - `FOREGROUND_SERVICE_MEDIA_PROJECTION` পারমিশন এবং `PermanentScreenCaptureService` নিবন্ধন করা হবে।
- **ফাইল**: `app/src/main/java/com/example/MainActivity.kt` ও `OmniAgentViewModel.kt`
  - `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())` দিয়ে `MediaProjectionManager.createScreenCaptureIntent()` যুক্ত করা হবে।
  - প্রথমবার স্ক্রিন শেয়ার অন করলে বা ইউজার "স্ক্রিনে দেখ" বললে যদি পারমিশন না থাকে, তবে সাথে সাথে পারমিশন ডায়ালগ ওপেন হবে। একবার Allow করার পর থেকে ভয়েসে **"স্ক্রিনে দেখ"** বললেই অটোমেটিক স্ক্রিন শেয়ার ও লাইভ স্ক্রিন ভিশন চালু হয়ে যাবে।

### C. ৪-দিকে স্ক্রিন কন্ট্রোল ও রিয়েল মানুষের ভয়েস (Omni-Directional Control & Natural Human Voice)
- **ফাইল**: `app/src/main/java/com/example/service/CubeAccessibilityService.kt` ও `DeviceControlExecutor.kt`
  - ডানে (`SWIPE_RIGHT`), বামে (`SWIPE_LEFT`), উপরে (`SWIPE_UP` / `SCROLL_UP`), নিচে (`SWIPE_DOWN` / `SCROLL_DOWN`), যেকোনো স্থানাঙ্কে ট্যাপ (`TAP_XY`), ডাবল ট্যাপ, লং প্রেস এবং স্ক্রিনের যেকোনো লেখার ওপর নিজে থেকে ক্লিক করার সক্ষমতা আরও নিখুঁত করা হবে।
- **ফাইল**: `app/src/main/java/com/example/service/AgentTtsModule.kt` ও `VoiceAgentBridge.kt`
  - **Natural Human Voice (`NATURAL_HUMAN`)**: নতুন ডিফল্ট ভয়েস প্রোফাইল ও ডাইনামিক বাক্য বিরতি (conversational prosody) যোগ করা হবে এবং এআই সিস্টেম প্রম্পট আপডেট করা হবে যাতে এজেন্ট কখনোই রোবটিক লিস্ট বা কোড না পড়ে, বরং একজন মানুষের মতো স্বাভাবিক কথ্য ভাষায় ("জি স্যার, আমি স্ক্রিনে দেখছি...", "ঠিক আছে স্যার, এখনই করে দিচ্ছি") কথা বলে।
  - এজেন্ট কথা বলার সময়ও ব্যাকগ্রাউন্ড ভয়েস বার্জ-ইন ডিটেকশন সক্রিয় থাকবে যাতে আপনি কথা বললেই সে সাথে সাথে চুপ হয়ে আপনার কথা শোনে।

---

## ৩. ভেরিফিকেশন প্ল্যান (Verification Plan)
1. `compile_applet` চালিয়ে নিশ্চিত করা হবে যে নতুন `PermanentScreenCaptureService`, `MultimodalLiveService` (`gemini-2.0-flash-exp`), এবং `MainActivity` কোনো এরর ছাড়াই বিল্ড হচ্ছে।
2. স্ক্রিন শেয়ার বাটন ও `"স্ক্রিনে দেখ"` ভয়েস কমান্ড টেস্ট করে প্রথমবার পারমিশন পপআপ এবং পরবর্তীবার পারমিশন ছাড়াই তাৎক্ষণিক স্ক্রিন শেয়ারিং যাচাই করা হবে।
3. লাইভ ভয়েস স্ট্রিমিং চলাকালীন মাঝপথে কথা বলে **Instant Barge-In** এবং ডানে-বামে-উপরে-নিচে অটোমেটিক স্ক্রিন কন্ট্রোল যাচাই করা হবে।
