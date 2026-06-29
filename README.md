## Rung — Local Multiplayer (Court Piece / Hukam)

A 4-player trick-taking card game for Android. Four real people play on four phones
over the same Wi-Fi or hotspot — no internet, no server, no bots. One phone **hosts**
(it holds the full game state and deals); the other three **join** with a 4-digit code.

Built with Kotlin + Jetpack Compose. Package `com.champ.rung`, `minSdk 26`,
`compile/target 35`, AGP 8.7.3, Kotlin 2.0.21.

### How a game works
- **Host** taps *Create room* → gets a code (e.g. `4582`) and starts a hotspot or joins shared Wi-Fi.
- **Three players** tap *Join room*, pick the room from the nearby list (or type the host's IP), and enter the code.
- When all four seats are filled, the host taps *Start*.
- Toss, dealing, Rung selection, and all 13 tricks play out with each phone seeing **only its own hand**.

Teams are fixed by seat: **seats 0 & 2 = Team A**, **seats 1 & 3 = Team B** (partners sit opposite). Turn order is anti-clockwise.

### Where the rules live in the code
The whole rulebook is implemented authoritatively on the host in
`engine/HostGameController.kt`:

| Rule (from the algorithm) | Code |
|---|---|
| Toss: low card → *next* player selects Rung; tie re-tosses | `runTossThenDeal()` |
| Deal 5 → choose Rung → 4 → 4, anti-clockwise from selector | `startDealing()`, `dealRound()`, `onChooseRung()` |
| Follow-suit enforcement | `onPlay()` |
| Trick winner: highest trump, else highest of lead suit | `winningPlay()` |
| Lifting after 5 tricks, then every 2; **Ace can't take the first lift** | `applyLifting()` |
| Court (rung team takes all 13) → partner selects next | `finishRound()` |
| GC (rung team takes 0) → next anti-clockwise selects | `finishRound()` |
| Normal win → next anti-clockwise selects | `finishRound()` |
| Disconnect → pause; rejoin by name or seat takeover; host-cancel | `onGone()`, `onHello()`, `isPaused()` |

Cards/deck are in `model/Cards.kt`; the wire protocol and the per-seat state
snapshot are in `model/Messages.kt`.

### Architecture (host-authoritative)
- **Networking:** line-delimited JSON over plain **TCP**. The host binds the first
  free port in `47815–47824`. Clients are auto-discovered with **NSD/mDNS**
  (`_rung._tcp.`); a manual **host-IP** field is the fallback when discovery is
  blocked. See `net/` (`HostServer`, `GameClient`, `NsdHelper`, `NetUtils`).
- **Engine as an actor:** all state mutations run on a single coroutine consuming a
  `Channel<Action>` on `Dispatchers.Default`, so the logic is effectively
  single-threaded (no locks) and socket writes never hit the main thread. Timed
  phases (toss reveal, deal pauses, trick reveal) use `delay()` inline.
- **State flows down, actions flow up:** the host builds a personalised `TableState`
  per seat and pushes it to each client; clients send only `Hello` / `ChooseRung` /
  `Play`. UI is driven by `GameViewModel` → Compose (`ui/`).

### Build & run
Requires **JDK 17** and Android Studio (Ladybug or newer) with SDK 35.

```
# from the project root
./gradlew assembleDebug
# or just open the folder in Android Studio and Run on each device
```

Install the debug APK on all four phones (`app/build/outputs/apk/debug/`), put them
on one Wi-Fi/hotspot, host on one, join on the rest.

### Troubleshooting
- **Room doesn't appear in the nearby list?** Some hotspots/routers block mDNS. Use
  *Or join by host IP* — the host's IP is shown on its lobby screen.
- **Can't connect at all?** Check both phones are on the *same* network and that
  **AP isolation / client isolation** is off on the router/hotspot. The app tries
  ports `47815–47824`.
- **Connection drops mid-game?** It auto-reconnects; just keep the host app in the
  foreground (background sockets get killed by the OS).

### Publishing to Play
- Change `applicationId` (and ideally the package) away from `com.champ.rung` to your
  own before uploading.
- Reuse your SpeedMeter **GitHub Actions** release-signing workflow (PKCS12 keystore
  via encrypted secrets) — `release` here already has `minify` + the
  kotlinx-serialization keep rules in `proguard-rules.pro`.
- No always-on wake lock is used (just `FLAG_KEEP_SCREEN_ON` while the app is
  foreground), which stays clear of the March-2026 wake-lock policy.
