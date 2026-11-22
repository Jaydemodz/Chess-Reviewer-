# Chess Reviewer

An Android overlay that watches a chess game on screen, reads the board, and shows the best
move from Stockfish. Protected screens (`FLAG_SECURE`) are read through root, or through
[Shizuku](https://shizuku.rikka.app) on a phone without root.

## Using it

1. Tap **Allow overlay** and switch on "Display over other apps".
2. Tap **Start** and accept the screen-capture prompt.
3. Open the chess game so the whole board is visible; a small panel floats at the bottom.
4. With the pieces in the start position, tap **Learn** (once per piece set).
5. Play. The board is found automatically and the best move updates by itself.

`Flip` swaps which side is at the bottom, `Turn` swaps whose move it is, `Save` writes what the
app sees to `Pictures/ChessReviewer` so you can send it along when something looks wrong.

## Stockfish strength (Elo)

Stockfish plays at full strength by default. The `Elo` button on the floating panel cycles
through preset levels (full strength, then about 2500, 2000, 1600, 1320, 1100, 900 and 700
Elo), and the main screen has a strength slider with a "Full strength" checkbox for finer
control (600-3190, in 10-Elo steps). Both write the same setting, so whichever you use, the
overlay and the manual position analyzer follow it. The choice is remembered across launches.
Above 1320 it is Stockfish's own `UCI_LimitStrength` / `UCI_Elo` pair; below that the app
switches to the universal `Skill Level` option, which every Stockfish build understands, so
beginner levels stay reachable. At startup the app reads the engine's own `uci` answer and
asks the binary which of the two options it declares and with which ranges, so the same
controls work with new and old Stockfish builds alike.

## Protected screens (FLAG_SECURE)

MediaProjection, `AccessibilityService.takeScreenshot()` and plain `screencap` all leave secure
layers out, so those windows come back black. The app gets the real pixels two ways:

| Route | Needs | Works on |
| --- | --- | --- |
| Root | a working `su` (Magisk, KernelSU, APatch) | every Android version here |
| Shizuku | Shizuku installed, running, and access granted | Android 11 and newer |

The Shizuku route runs `SecureShotService` in a process Shizuku starts with the shell uid
(2000), asks the system for a display capture with
`ScreenCapture.CaptureArgs.Builder#setCaptureSecureLayers(true)`, and hands the frame back to
the app in chunks over AIDL. No root, no Xposed or LSPosed module.

### One capture file, every Android release

The class that can take a secure screenshot changed between releases, so `DisplayCapture.kt`
holds one route per generation and picks between them on the running phone. All of it is
hidden API reached by reflection, so the file compiles once and probes the phone it is on.

| Android | Route |
| --- | --- |
| 14 and newer | `WindowManagerService.captureDisplay(CaptureArgs, ScreenCaptureListener)`, through `android.window.ScreenCapture` |
| 12 and 13 | the same `ScreenCapture` call, with the display id first: `captureDisplay(int, CaptureArgs, listener)` |
| 11, and the Android 13 fallback | `SurfaceControl.captureDisplay(DisplayCaptureArgs)` on SurfaceFlinger's display token |
| anything left | the stock `screencap` binary, which is not secure-aware but always exists |

A route that this build does not declare, or that refuses the call, is recorded and skipped;
the next one is tried and the error text from all of them comes back in the panel when the
frame is still black. That is what makes one APK work from Android 11 to Android 15.


### Setting up Shizuku

1. Install Shizuku from the Play Store (`moe.shizuku.privileged.api`).
2. Enable Developer options and Wireless debugging, then start Shizuku from inside the app
   (Shizuku shows a pairing code and "Start via wireless debugging").
3. Open Chess Reviewer: the panel shows whether Shizuku is installed, running and allowed.
   Tap **Grant Shizuku access** and allow it.
4. Play as usual. When a game blocks screenshots, the overlay switches to the Shizuku frame
   by itself and says so; when capture works again it switches back.

Shizuku has to be started again after a reboot. If it is not running, protected screens stay
black and the panel says so; type the position into the app instead.

## Build

The GitHub Actions workflow (`.github/workflows/build.yml`) downloads the arm64 Stockfish
release into `app/src/main/jniLibs/arm64-v8a/libstockfish.so` and builds the debug APK. The
Shizuku client libraries (`dev.rikka.shizuku:api` and `:provider`, 13.1.5) come from Maven
Central, which `settings.gradle.kts` already lists.
