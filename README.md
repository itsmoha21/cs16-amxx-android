# CS16 AMXX Android

Counter-Strike 1.6 for Android with **AMX Mod X 1.9**, **Metamod** and **YaPB bots**.
Everything is included — you do not need a PC to install or to make plugins.

Two apps work together:

| App | What it does |
|-----|--------------|
| **CS16AMXX Engine** | The game engine (Xash3D FWGS) — video, audio, touch controls |
| **CS16AMXX Game** | Launches CS 1.6, installs the AMXX addons, compiles `.sma` plugins on the phone, edits `plugins.ini` |

---

## Download

Get these 4 files from the [Releases page](../../releases):

| File | What it is |
|------|-----------|
| `CS16AMXX-Game-v1.35.apk` | the game app |
| `CS16AMXX-Engine-v1.35.apk` | the engine app |
| `CS16AMXX-cstrike-addons.zip` | AMXX addons for `cstrike/` (also bundled inside the game app) |
| `CS16AMXX-valve-addons.zip` | AMXX addons for `valve/` |

## Install (4 steps)

### Step 1 — Install both APKs

Tap each APK file and install it. If Android asks, allow
"install from unknown sources" for your file manager/browser.

### Step 2 — Copy your game files

These apps do **not** include the copyrighted CS 1.6 game files. Copy your own
game data to the phone storage:

```
/storage/emulated/0/xash/
├── valve/    ← Half-Life base files
└── cstrike/  ← Counter-Strike files (maps, models, sound, ...)
```

Any file manager works. Create the `xash` folder yourself if it is not there yet.

### Step 3 — Install the addons

1. Open the **CS16AMXX Game** app.
2. The STATUS card shows what is missing.
3. Turn on the **AMX Mod X** switch (and **YaPB bots** if you want bots).
4. Tap **Re-install addons** — the addons (AMXX + Metamod + bots) are bundled
   inside the app and get extracted automatically.
   *(Manual alternative: unzip `CS16AMXX-cstrike-addons.zip` into
   `/storage/emulated/0/xash/` so it merges with your `cstrike/` folder.)*

### Step 4 — Play

Tap **LAUNCH**. The game starts with AMXX loaded. Bots: use `bot_add`,
`bot_quota` and the other YaPB commands.

---

## In-app tools

The game app has three tabs on the bottom bar:

### LAUNCH
- **AMX Mod X** switch — turns the AMXX + Metamod chain on/off
- **YaPB bots** switch — turns the bots on/off
- **Re-install addons** — re-extracts the bundled addons (safe to run anytime)
- **LAUNCH** — starts the game

### PLUGINS
Edit any `plugins*.ini` from `cstrike/addons/amxmodx/configs/` on the phone:
- search box — type part of a file name, tap a result to open it
- line numbers on the left that scroll with the text
- `;` comments shown in gray italic
- **SAVE** writes the file back

### COMPILER
Compile `.sma` scripts to `.amxx` plugins on the phone — no PC needed:
- the list shows all scripts from `cstrike/addons/amxmodx/scripting/`
- tick the ones you want, tap **COMPILE SELECTED** (or **COMPILE ALL**)
- the compiled plugin appears in `addons/amxmodx/plugins/`
- full compiler log with **COPY** / **CLEAR**

---

## Building from source

You need: [Android Studio](https://developer.android.com/studio) (it provides
the SDK, NDK and JDK).

```
git clone --recursive https://github.com/itsmoha21/cs16-amxx-android.git
cd cs16-amxx-android/android
./gradlew --no-daemon :app:assembleGitRelease
```

The APK appears in `android/app/build/outputs/apk/git/release/`.

Everything the APK needs is already in this repository:
- the engine and client sources
- the game DLL (`3rdparty/ReGameDLL_CS`, vendored)
- prebuilt AMXX / Metamod / amxxpc binaries (`android/app/src/main/jniLibs`)
- the addons bundle (`android/app/src/main/assets/addons.zip`)

## What's inside

- [fwgs/xash3d-fwgs](https://github.com/fwgs/xash3d-fwgs) — engine
- [Velaron/cs16-client](https://github.com/Velaron/cs16-client) — game client
- [rehlds/ReGameDLL_CS](https://github.com/rehlds/ReGameDLL_CS) — game DLL,
  vendored with latest upstream fixes and an Android build fix
- AMX Mod X 1.9, Metamod, YaPB bots, ReAPI

## Credits

- **itsmoha21** — project owner; AMX Mod X 1.9 Android port and build,
  addons bundles, launcher app, on-device compiler and plugins editor
- Xash3D FWGS team — the engine
- Velaron — cs16-client
- ReGameDLL-CS / ReHLDS team — the game DLL

## License

Source code is under the licenses of the respective subprojects
(see each folder's LICENSE file). Game assets are not included.
