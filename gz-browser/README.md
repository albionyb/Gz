# Gz Browser

A lightweight Android browser made for playing Granblue Fantasy. It is built on Android System WebView and adds the tools a player needs mid-battle: quick-access buttons, notes, a drop tracker and an ad blocker, all in a clean full-screen layout.

## Features

**Browsing**
- Tabs with a Firefox-style tab grid, tab restore after restart, and a home screen with shortcuts
- **Tab groups:** make your own named, colored groups from the tab grid (📁 button, or long-press a tab). Long-press a tab and drag it to reorder it, onto a group header to move it in, or onto "Ungrouped" to take it out. Group headers can be dragged to reorder, tapped to collapse, and have a ⋮ menu (rename, color, close tabs, delete). Long-press and release without moving opens the tab's menu
- Tab previews are copied from the real screen, so scrolled pages and game screens no longer show black
- Built-in ad and tracker blocker (can be switched off per site, and never active on the game's own sites)
- Desktop-site mode per site, page width slider, text mode and optional pinch zoom
- History, file upload support, and a download list (progress, cancel, retry, open, share, delete)
- Clear cache, clear one site's data, or clear everything
- **Profiles:** (Main can be renamed and recolored too, and every tab card shows its profile badge) separate logins and site data (for example a second game account). Long-press + in the tab list to open a tab in a profile, tie a bookmark or shortcut to one, or reopen a page in another profile. Needs a recent Android System WebView
- **Gestures** (off by default): edge swipes, address bar and bottom bar swipes, two and three finger swipes. Every gesture can be mapped to an action, and gestures can be switched off per site
- Find in page
- Settings grouped into pages (Browsing, Profiles, Privacy & safety, Appearance, Screen & bars, Bubbles & floating buttons, Gestures, Bookmarks & backup, Downloads & storage)

**Screenshots**
- Viewport, full-page and crop screenshots

**Game tools**
- **Adaptive icons:** bookmark, floating-button and menu icons follow the chosen color theme and accent color
- **Bookmark bubbles:** a row of square shortcut bubbles with custom icons, adjustable height and opacity, plus folders and backup to file. Long-press a bubble and drag to reorder it, or drop it on a folder to move it in (release without moving to get the edit menu). A small handle above the row hides and shows it
- **Floating buttons:** drag them anywhere, lock them in place, and set size and opacity for each one. Actions include back, forward, reload, home, tabs, menu, screenshot, full screen, downloads, a saved link, Notes and Drop tracker
- **Notes:** a checklist with red ✗ / green ✓ per task, plus plain notes. Add a count (e.g. Siete + 10) for a battle counter: tap the number to count down, and at 0 the note is done and struck through
- **Drop tracker:** counts fights since the last drop, total drops and the average per drop. One item shows at a time (swipe for the next), and you tap animated treasure chests: a shaking chest for no drop, an opening chest for a drop
- **Spark calculator**
- Notes and Drop tracker open as movable panels that stay open while you play. You can change their size and opacity, lock them in place, or switch back to a normal popup

**Display and safety**
- Auto-hide address bar, full screen mode and hide-all-bars mode
- Orientation lock and keep-screen-on
- "Press back twice to exit" guard to prevent accidental exits mid-battle
- Six color themes plus a custom accent color

## Build

Push the project to GitHub. The workflow in `.github/workflows/build.yml` builds the APK (Actions > Build APK) and publishes a release. The build downloads an ad-server blocklist, and if that fails it uses the bundled list.

## Requirements

- Android 8.0 (API 26) or newer
- An up-to-date **Android System WebView** (or Chrome on older Android versions) from the Play Store

## Disclaimer

- This is an unofficial, fan-made project. It is **not affiliated with, endorsed by or sponsored by** Cygames, Inc. or the makers of Granblue Fantasy.
- "Granblue Fantasy" and all related names, logos and artwork are the property of their respective owners.
- The app only loads the game's public website in a browser. It does not modify the game or automate gameplay.
- Using third-party tools such as ad blockers, custom browsers or user-agent changes may go against the game's terms of service. Check the rules yourself and use this app **at your own risk**. The author is not responsible for any account action, data loss or other problems that result from using it.
- Bookmarks, notes, history and settings are stored on your device.
- The software is provided "as is", without warranty of any kind.
