# Orbit

A One UI–style home screen for the Nothing Phone (3a): swipe up for an apps screen that pages sideways, with your wallpaper showing through behind it. Uses Google themed icons, a Nothing-style monochrome look, or any installed icon pack.

## Install
1. On the phone, download **[apk/Orbit.apk](apk/Orbit.apk)** (or the `latest` release) and install it.
2. Open **Orbit settings** from the app list → **Default home app** → choose **Orbit**.
3. Press Home.

To go back to the Nothing launcher: Settings → Apps → Default apps → Home app → Nothing Launcher.

## Using it
| Do this | What happens |
|---|---|
| Swipe up on home | The apps screen opens and follows your finger. |
| Swipe sideways on the apps screen | Next page of apps (A–Z). |
| Swipe left to right on the first apps page | Private space (Android 15): Unlock asks for your PIN or fingerprint; Lock hides it again. |
| Swipe down on the apps screen, or Back | Closes it. |
| Swipe down on home | Notification shade. |
| Tap the search bar | Search apps; Enter opens the first match. |
| Long-press an app | Menu: Add to Home / Remove / App info / Uninstall. |
| Long-press and drag | Move it. From the apps screen it lands on home; hold at the screen edge to change page; drop on the dock to pin it. |
| Long-press empty home space | Wallpaper and settings. |
| ⋮ on the apps screen | Settings. |

## Settings
- **Icons:** app icons · Google themed (Material You) · Nothing style (black and white) · any installed icon pack (Nova/ADW format).
- **Apps screen:** background dim from 0% (fully transparent) to 80%, optional wallpaper blur, and a grid of 4×5, 4×6, 5×5 or 5×6.
- **Home screen:** grid size, app names, swipe down for notifications.

## Permissions
Only these: open the notification shade, ask Android to uninstall an app (Android still asks you to confirm), and see Private space (Android grants this only to the default home app). No internet, no storage. CI fails the build if anything else appears.

Every push to `main` builds the APK in GitHub Actions, commits it to `apk/`, and updates the `latest` release. All builds share one signing key, so updates install over the old version and keep your layout.
