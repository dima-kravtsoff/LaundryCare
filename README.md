# Closet Care

An Android app that remembers how to wash and dry every piece of clothing you own.

When you buy something, you photograph it (up to five shots) and its care tag (up to four, since fibre content and the symbols are often on separate labels). An AI model reads the tag and the app stores a care profile. On laundry day you photograph the garment, the app finds it in your closet and tells you exactly which program, temperature, spin and dryer setting to use on your machines.

The recommendations are tuned to a **Bosch 500 Series** pair, washer **WAT28401UC/14** and dryer **WTG86401UC/01**, transcribed from their use-and-care manuals: which temperatures and spin speeds each cycle actually offers, and how full the drum may be. A generic washer/dryer profile is included for other machines (Settings → Your machines).

## What it does

- **Add a garment**: garment photos + tag photos → *Read tag with AI* → review form. Fields the AI guessed rather than read are highlighted amber with a "check this" marker; they stay marked as unconfirmed on the detail screen until you confirm them. Or copy the care from a garment with the same tag, or type it in by hand.
- **Care guide** per garment: washer card (program, temperature, spin, max load, options, warnings), dryer card (program, heat, or air-dry instructions), what the tag says, and "about N of these fit" for the load limit. Where it earns its place, the card also says when a mesh bag or a colour-catcher sheet is worth using. Share it as text.
- **Laundry tips** (book icon on the closet): oxygen bleach vs chlorine, colour catchers, vinegar and soda, HE detergent dosing, caring for a front-loader and a condenser dryer, loading, stain first aid, and the bleach-mixing safety rules.
- **Snap it to find it**: photograph a garment in the laundry basket and the app matches it against the closet. Every garment gets a one-sentence visual description at save time, so the matcher sends text for the whole closet and small reference photos only for the 30 most recent items, which keeps requests fast and cheap on mobile data.
- **Plan a load**: tick the garments going in (or snap-to-add) and *Check this load*. Constraints are intersected, not averaged: lowest temperature, gentlest cycle, lowest spin, lowest dryer heat. Wool, hand-wash-only, bleeding, dry-clean-only and colour conflicts are pulled out with a reason, and the plan names the garment that forced each setting ("Cold because of the silk blouse") so you can pull one thing rather than run everything cold. Pulled items get a suggested second load. Loads that exceed a cycle's drum limit are flagged.
- **Search, filter, sort** the closet (machine wash / hand wash / no tumble dry / dry clean / needs a check; recent or A–Z).
- **Edit** any garment, retake photos, or re-read the tag with AI.
- **Backup**: export the closet with photos as a zip, or care data only as a small JSON, and import it back on any device (merge or replace). The file carries a schema version.
- **Two AI providers**: Gemini or Claude, each with its own key and model. If the configured Gemini model is out of quota (a free key gets about 20 requests a day per Flash model), the app walks down a ladder of other Flash models for that one request and tells you which one answered and when the quota resets; your chosen model is kept for tomorrow. Only a retired model id (404) changes the saved default. If the whole ladder is spent, the key is asked which models it can still use.

Everything except the two AI features works offline. Garments and photos stay on the device; only the photos you send are uploaded, and only to the provider you chose.

## Build and install

Requirements: Android Studio (or just its JDK + the Android SDK) and a device on Android 8.0 or newer.

```
./gradlew :app:assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # installs on the connected device
./gradlew :app:testDebugUnitTest      # recommendation, load planner, schema, backup and model-ladder tests
```

The unit tests run the real Gemini client against a mock server. To also run it against the live API with a photo of your own (three extra tests, a handful of requests):

```
GEMINI_API_KEY=... LAUNDRY_TAG_PHOTO=/path/to/tag.jpg LAUNDRY_GARMENT_PHOTO=/path/to/garment.jpg ./gradlew :app:testDebugUnitTest --tests '*GeminiLiveTest*' -i
```

The build machine needs about 4 GB free for the Gradle and Kotlin daemons together; `gradle.properties` caps them at 2 GB and 1.5 GB. If a build dies with "insufficient memory for the Java Runtime Environment", close other heavy programs or run `./gradlew --stop` to clear stale daemons.

`local.properties` must point at your SDK. On Windows use forward slashes and escape the drive colon, or Gradle resolves the path relative to the project:

```
sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk
```

## First run

1. Settings → Tag reader: choose Gemini or Claude and paste an API key (Gemini: aistudio.google.com → Get API key; Claude: platform.claude.com → Settings → API keys).
2. Tap **Check key & list models** and pick a model from what the key offers. Don't guess model names. The check also remembers which models the key can reach, so the tag reader can try a Pro model first when there is one and never wastes an upload on a name that doesn't exist.
3. Save. Add your first garment.

Reading one tag costs two or three requests (locating the symbol row, then reading it). On Gemini's free tier that is roughly eight tags a day on the best Flash model before the app steps down to older or lighter ones; the snackbar says when that happens.

## Project layout

```
app/src/main/java/com/laundrycare/
  data/      Models (care profile, garment, closet file), JSON store, photo store, settings, backup
  care/      Machine profiles, care → settings recommender, load planner, load-fit estimates
  ai/        Provider-neutral schema + prompts, Gemini and Claude clients
  ui/        Compose screens and the single ViewModel
app/src/test/  JVM unit tests for the pure logic
laundry-care.html   The earlier single-file web app this was built from
```

Photos are stored upright at 1600 px on the long side; a 384 px thumbnail is kept for the matcher. Drafts survive the camera app pushing the process out of memory.

## Notes

- The API key lives in the app's private preferences. Fine for a personal build; anything published should put a proxy in front of the provider.
- Android's cloud backup is switched off for the app (the key would go with it); use Export in Settings.
- Load-fit numbers ("about 6 of these") are deliberate estimates from typical garment weights and volumes. Go by eye.
