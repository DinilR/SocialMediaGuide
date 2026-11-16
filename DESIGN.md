# SocialMediaGuide — Design Document

An Android app that stops doomscrolling in social media apps without blocking them outright. You can still watch what you came for; the app only steps in when you start scrolling into the endless feed of "next" content.

Supported apps: **YouTube**, **Instagram** and **Facebook**.

| App | Allowed | Blocked (each a switch in the app) |
|---|---|---|
| YouTube | Feed, search, links, the video you open | Scrolling into related videos · swiping to the next Short · (optional) all Shorts |
| Instagram | Home feed, search, profiles, messages, a Reel you open | Swiping to the next Reel · (optional) all Reels · scrolling the Explore grid (search still works) |
| Facebook | Home feed, posts, a video or Reel you open | Swiping to the next Reel · (optional) all Reels · scrolling the Video tab feed |

All apps share one engine (`guard/`): each app has a detector that reports one of three screen types, and the service treats them the same way:
- **Feed**: a list that can be looked at but not scrolled down (YouTube related videos, Instagram Explore, Facebook Video tab). The panel covers the list area.
- **Pager**: a swipe-to-next player (Shorts, Reels). The first item plays; swiping on is covered, or the whole player if "entirely" is on.
- **Other**: left alone.

Instagram is recognised by view ids and which bottom tab is selected (`clips_tab`, `search_tab`). Facebook reports almost no view ids, so it is recognised by tab descriptions ("Video, tab 4 of 6") and the "Reels" header, which only counts while the tab bar is hidden (otherwise the home feed's Reels shelf would match). Description clues are English only.

| | |
|---|---|
| **Platform** | Android (phone) |
| **Stack** | Kotlin · Jetpack Compose (Material 3) · Android Accessibility Service · SharedPreferences |
| **Connectivity** | 100% offline: no server, no account, no `INTERNET` permission |
| **Min / Target SDK** | 26 (Android 8.0) / 35 |
| **Status** | v1 built; YouTube detection needs calibrating on a real phone (see 8) |

---

## 1. Goals

1. Let the user **open any YouTube video** from the home feed, a shared link, or search, and watch it normally.
2. Once a video is open, **block scrolling down into "Up next" / related videos** — the main doomscroll path on the watch page.
3. **Block swiping through Shorts** (optionally block Shorts entirely).
4. Be **gentle and clear** when blocking: show a full-colour panel that explains what happened and offers *Back to video* or *Leave*.
5. Be **easy to switch on and off**, with simple per-rule toggles and a "blocked today" counter for motivation.

### Non-goals (for v1)
- The YouTube website in a browser (Chrome etc.).
- Modifying or replacing the YouTube app (no patched APKs, no custom YouTube client).
- Time limits, schedules, passwords / "strict mode".
- Other apps (Instagram, TikTok, Facebook) — planned, see roadmap.

---

## 2. How it works

Android does not let one app change another app's screen. It does provide the **Accessibility Service API**, which lets an app (with the user's explicit permission) read the window layout of other apps, react to events such as "the user scrolled", and draw an overlay on top. Digital-wellbeing apps use this API for exactly this purpose.

```
 YouTube app
    │  Android sends events: window changed · content changed · view scrolled
    ▼
 GuardService  (AccessibilityService, listens to com.google.android.youtube only)
    │
    ├─► YouTubeDetector  — reads the on-screen node tree and decides the screen:
    │        HOME / SEARCH / OTHER   → allow
    │        WATCH (at the top)      → allow
    │        WATCH (scrolled down)   → block "related videos"
    │        SHORTS (swiped)         → block "shorts"
    │
    └─► BlockOverlay     — a TYPE_ACCESSIBILITY_OVERLAY window drawn over YouTube
             • on the watch page: covers the area below the video player,
               so the video itself keeps playing and stays usable
             • in Shorts: covers the whole screen
             • buttons: "Back to video" (scrolls the list back to the top)
                        "Leave"         (Android Back)
```

### 2.1 Detecting the screen

YouTube does not offer an API for this, so the detector recognises screens from **clues in the node tree**: view resource ids (e.g. the watch-page player, the Shorts player / pager) and visible text or content descriptions. It never reads video titles, comments or account data beyond what it needs to classify the screen, and stores nothing from YouTube.

- **Several clues per screen.** Each screen is matched by a list of alternative ids and texts, so a single YouTube update that renames one view does not break detection.
- **Ids live in one file** (`youtube/YouTubeRules.kt`), so they are easy to update when YouTube changes.
- **Diagnostics mode** (a switch in the app) writes the detected screen and the visible view ids to logcat (`adb logcat -s SMG`), so new ids can be found quickly after a YouTube update.

### 2.2 Detecting "scrolling down" on the watch page

When a video is open, the area below the player is a scrolling list: title and buttons first, then comments teaser, then related videos. Scrolling it raises `TYPE_VIEW_SCROLLED` events.

- A scroll on the watch page **downward** (positive `scrollDeltaY`, or a first visible item index above the allowance) triggers the block.
- A small **allowance** (default: the first item, i.e. title, like / share buttons and description teaser) is left free, so the user can still like or share the video.
- Rotating to full screen or using the player itself is never blocked.

### 2.3 Shorts

- **Default:** a Short opened from a link, search or the feed plays; **swiping to the next Short** is blocked.
- **Option "Block Shorts entirely":** as soon as the Shorts player appears, the overlay is shown and Back is offered.

### 2.4 The block overlay

A Compose-free plain `View` (fast to show, no lifecycle needed inside a service), styled to match the app:

```
┌───────────────────────────────┐
│  ▶ video keeps playing here   │   ← not covered
├───────────────────────────────┤
│                               │
│       ✋  SCROLL STOPPED       │
│   You came for this video.    │
│   The feed below is blocked.  │
│                               │
│  [ BACK TO VIDEO ]  [ LEAVE ] │
│                               │
└───────────────────────────────┘
```

- *Back to video*: sends `ACTION_SCROLL_BACKWARD` to the list until it is at the top, then hides the overlay.
- *Leave*: `GLOBAL_ACTION_BACK`, then hides the overlay.
- The overlay hides automatically when YouTube leaves the watch / Shorts screen.
- Every block is counted ("blocked today").

---

## 3. App screens (Compose)

### 3.1 Home
```
┌───────────────────────────────┐
│ SOCIAL MEDIA GUIDE            │
│ ● Guard is ON   / ○ OFF       │  ← status, big and coloured
│ [ Turn on in Settings ]       │  ← only when the service is off
│───────────────────────────────│
│ Blocked today      12         │
│  · related videos   9         │
│  · shorts           3         │
│───────────────────────────────│
│ YOUTUBE                       │
│ Block related videos   [on ]  │
│ Block Shorts swiping   [on ]  │
│ Block Shorts entirely  [off]  │
│───────────────────────────────│
│ Diagnostics (logcat)   [off]  │
└───────────────────────────────┘
```

### 3.2 Turn-on guide
Shown when the service is off. Explains, before sending the user to Android settings, exactly what the service can see and why (required by Google Play's accessibility policy and simply honest):

> SocialMediaGuide uses Android's Accessibility Service to see which YouTube screen is open and when you scroll, so it can cover the feed of related videos. It only watches the YouTube app, stores nothing it sees, and never sends anything off your phone.

Then a button opens **Settings → Accessibility → SocialMediaGuide**. On Android 13+, sideloaded apps may also need *App info → ⋮ → Allow restricted settings* first; the guide shows that step too.

---

## 4. Data

No database is needed in v1. Everything lives in `SharedPreferences`, exposed as a `StateFlow` so the UI and the service share it:

| Key | Type | Default |
|---|---|---|
| `yt_block_related` | Boolean | true |
| `yt_block_shorts_swipe` | Boolean | true |
| `yt_block_shorts_all` | Boolean | false |
| `diagnostics` | Boolean | false |
| `stats_day` | String (ISO date) | today |
| `stats_related`, `stats_shorts` | Int | 0 — reset when `stats_day` changes |

---

## 5. Package layout

```
com.socialmediaguide
├── MainActivity.kt            Compose host
├── settings/GuardSettings.kt  SharedPreferences + StateFlow, daily stats
├── service/GuardService.kt    AccessibilityService: events → detector → overlay
├── service/BlockOverlay.kt    the overlay window
├── youtube/YouTubeRules.kt    view ids and texts per screen (update here)
├── youtube/YouTubeDetector.kt classify screen, find player bounds and the list
└── ui/                        HomeScreen, theme
```

Service configuration (`res/xml/guard_service.xml`):
- `packageNames="com.google.android.youtube"` — only YouTube's events are delivered
- events: `typeWindowStateChanged | typeWindowContentChanged | typeViewScrolled`
- `canRetrieveWindowContent="true"`, `flagReportViewIds`, `notificationTimeout="100"`
- `isAccessibilityTool="false"` with the disclosure in 3.2

---

## 6. Limitations and risks

| Risk | Mitigation |
|---|---|
| YouTube updates rename views and break detection | Multiple clues per screen, ids in one file, diagnostics mode to find new ids |
| User can turn the service off | Intended — it's a helper, not parental control. Strict mode is a later option |
| Play Store accessibility policy | Prominent disclosure + consent screen; sideloading the APK has no review |
| Android 13+ blocks accessibility for sideloaded apps by default | Guide shows the "Allow restricted settings" step |
| Battery | Only YouTube events are delivered; detection is a quick tree walk, throttled to 100 ms |

---

## 7. Roadmap

| Version | Scope |
|---|---|
| **v1** | YouTube: block related videos on the watch page, block Shorts swiping / all Shorts, overlay, home screen with toggles and daily stats, diagnostics |
| **v2** | Limit home-feed scrolling (e.g. first N rows), daily "unlock for 5 minutes", strict mode delay ("wait 10 s to disable") |
| **v1.1** ✅ | Instagram (Reels, Explore) and Facebook (Reels, Video tab) on a shared engine; panel closes itself when you leave the app |
| **v3** | TikTok, YouTube in the browser, one `Rules` + `Detector` per app |
| **v4** | Weekly stats chart, schedules (e.g. block only after 10 pm) |

---

## 8. Test status

Tested on the emulator (Android 15, YouTube 19.17.42):

| Check | Result |
|---|---|
| App home screen, toggles, guard status | ✅ |
| Service binds and receives YouTube events | ✅ |
| Diagnostics log of view ids and scroll events (`adb logcat -s SMG`) | ✅ |
| Block screen overlay (via *Preview the block screen*) and its buttons | ✅ |
| Real watch page and Shorts detection | ❌ not possible on the emulator: that YouTube version is too old, YouTube's servers reject its feed and search, and links open in Chrome instead |

Found on the emulator's home and search screens, so these ids are **not** used as watch-page clues: `results`, `watch_while_layout_coordinator_layout`, `slim_status_bar_player_container`, `pivot_bar`.

### Shorts / Reels fix (v1.2)

Bug reported from a real phone: swiping Shorts was not blocked, and leaving Shorts showed the block screen. Reproduced on the test harness (`testapp/`, a debug-only app that mimics a Shorts pager and a video page; debug builds of the guard treat it as YouTube). Causes and fixes:

| Cause (seen in the log) | Fix |
|---|---|
| Pager scroll events sometimes carry no distance or index (`dy=0 from=-1`) | Three independent swipe signs: distance, index change, or the next page peeking in |
| A pager with no view id (ViewPager2) was not recognised as Shorts at all | Shorts / Reels are also recognised **by shape**: a full-screen *vertical* pager whose page fills it (sideways tab pagers excluded) |
| Mid-swipe the screen read as "not Shorts" (half pages, or an empty read during a redraw), resetting the session | The same pager seen again within 2 s continues the same visit |
| Leaving Shorts animates the player away with scroll events | Only the pager's own scrolls count, and a block is confirmed 350 ms later only if Shorts is still open |

Harness results: ViewPager2 swipe blocked ✅ · RecyclerView swipe blocked ✅ · leaving Shorts without swiping, no block ✅ · home feed scroll, no block ✅ · video page scroll down blocked ✅ · *Back to video* returns to the top without re-blocking ✅.

### v1.3: second round from the phone

Reported: YouTube still let Shorts be swiped and blocked only when leaving Shorts; Facebook blocked moving between posts in the feed as if swiping Reels.

| Cause | Fix |
|---|---|
| The v1.2 "by shape" rule matched **page holders**: Facebook and YouTube put each main tab (Home, Video…) in a full-screen pager with the feed scrolling inside the page | A Shorts / Reels player must be a full-screen vertical pager whose page holds **no feed of its own** |
| "Tallest scrolling area" fallbacks (YouTube Shorts tab, Instagram Reels tab, Facebook "Reels" header / tab) picked the home feed as the player | Removed; only the player shape or full-screen Shorts / Reels ids count. Facebook's "Reels" header is just logged |
| The real YouTube app appears to send no usable scroll events for Shorts | **Next Short / Reel detection without scroll events**: the page's short texts (title, channel, caption; counts and times skipped) are fingerprinted when the visit starts; when fewer than a third of them remain, a different Short is on screen and it is blocked. Kept in memory only, cleared when the player closes |
| Mid-swipe misreads ended the visit, and a misread on the next screen made a newly opened Short look like "the same visit" (instant false block) | An empty read never changes the screen; mid-swipe the known player is kept only while it still has full-width, feed-less pages; a new window always starts a new visit |

Harness (`testapp/` now has a Facebook/YouTube-style home: sideways tab pages each with a feed and a "Reels" title, and an auto-advancing Short that moves on with no swipe): home scroll no block ✅ · opening a Short no block ✅ · next Short with no swipe signal blocked ✅ · ViewPager2 / RecyclerView swipe blocked ✅ · Back from Shorts then home scroll no block ✅ · video page scroll blocked ✅.

### v1.4: fixed from the phone's diagnostics log

Log from a Samsung SM-S9210, Android 16, YouTube 21.43.523, Facebook 587.0.0.39.55 (`Information/SocialMediaGuide/logs.log`):

- **Facebook Home** (`selected:home`, tabs visible) was classified as *Facebook Reels* via `pager-shape:…ViewPager`, and scrolling the feed (`src=list`, bounds 103–2340) counted as swiping the player.
- **YouTube home** (`youtube_logo`, `tabs_bar`, `view_pager`, `results`) was classified as *YouTube Shorts* the same way, so the block fired on the home screen, i.e. when leaving Shorts.
- **Real YouTube Shorts** report `reel_recycler` / `reel_player_page_container` (bounds 0,103–1080,2151) and scroll events with no distance or index (`dy=0 from=-1`).

| Cause | Fix |
|---|---|
| Both apps' main tab holder is a classic `ViewPager` (no sideways actions) whose accessibility page **is** the feed list; the "page holds no feed" check only looked inside the page | The check includes the page itself |
| The player match by position allowed 10% slack, so the feed list just inside the holder matched | Exact position (≤ 1% / 16 px) |
| YouTube home matched the player shape | YouTube uses its Shorts ids first; shape is ignored when home ids (`youtube_logo`, `tabs_bar`, `results`) are present |
| Every newly opened Short counted as "the same visit" (the sighting was recorded before the new-visit check), so its fingerprint was compared with the previous Short's: instant block on opening | The sighting is recorded after the check; a player found from a scroll goes through the same bookkeeping |

Reproduced first on the harness (its home now mimics the logged structure: a `ViewPager`-class holder whose page is the `list`) with v1.3: home scroll blocked as "Shorts". With v1.4, two full rounds pass: home scroll no block · opening a Short no block · next Short with no swipe signal blocked · ViewPager2 / RecyclerView swipe blocked · Back then home scroll no block · video page scroll blocked.

Not yet covered by a log: Facebook's real Reels player and Instagram.

### v1.5: third round of phone logs

- **Facebook Reels** blocking works (block after the swipe; *Back to my Reel* scrolls back to the opened Reel).
- **YouTube log 1** (swiped 3–4 Shorts, no block): YouTube 21.43 sends **no scroll events** for Shorts at all, and its `reel_recycler` is **not marked scrollable**. The page check looked for a scrollable view, found none and silently gave up (no "page fingerprint" line).
- **YouTube log 2** (Shorts → Back → home → scroll → block): `reel_time_bar` (then one of the Shorts ids) also exists on the home screen, so home was still "Shorts"; then the home feed `results` briefly filled exactly the player's area and passed as the player.

| Fix | Detail |
|---|---|
| Shorts ids | Only `reel_recycler`, `reel_player_page_container`, `reel_watch_player`; never while home ids are present; the player's position is `reel_recycler`'s |
| Player must look like a pager | A scroll source in the player's area must also have full-width pages and no feed |
| Page check without a scrollable view | Finds the player by position and id, scrollable or not |
| Page check without events | While a Shorts / Reels player is open it re-checks every 0.8 s (stops when the player closes) |
| Baseline | First settled read; later additions grow it. A different Short = a main text (≥ 10 chars) gone, ≥ 2 texts gone and ≥ 2 new |
| Back button | Hidden when the player can't be scrolled back (YouTube), and a new setting **Allow "Back to my Short / Reel"** hides it everywhere |
| More diagnostics | `player:` (id, class, scrollable, children), `page texts (…)` as code/length pairs (no text), `page check:` reasons |

Harness: a YouTube-like Shorts mode (`reel_recycler` + `reel_player_page_container`, not scrollable, no scroll events) reproduced log 1 on v1.3/1.4 (no block). v1.5: home scroll no block · opening Shorts (all modes) no block · next YouTube-like Short with no events blocked · ViewPager2 / RecyclerView swipe blocked · YouTube-like Short → Back → home scroll no block · video page blocked · setting off → block screen shows only Leave.

### v1.7: block screen buttons

From the v1.6 phone logs (Instagram `dy=-1222`, Facebook `dy=-1347`, YouTube "swipe down sent"), the back button returned to the Short watched **before**, in all three apps. Wanted instead: watch the Short that was blocked.

| Change | Detail |
|---|---|
| **Back to the Short / Reel** (renamed from "Back to my …") | Closes the block screen and keeps the blocked Short on screen; it becomes the new starting point (fresh fingerprint, start index reset), so the next swipe is blocked again. No scrolling back, so no gesture permission is needed (`canPerformGestures` stays false) and YouTube gets the button too |
| Setting **Allow "Back to the Short"** | Off = no back button; a blocked Short can't be watched |
| **Leave app** button | On every block screen (feeds and players): goes to the phone's home screen. Setting **Show "Leave app"** (on by default) |
| Layout | Buttons stacked: BACK TO THE SHORT · LEAVE · LEAVE APP |

Harness: Short swipe blocked → *Back to the Short* stays on the blocked Short #1 with no new block → next swipe blocked again ✅; same on the YouTube-like player (no events, not scrollable) ✅; *Leave app* → launcher ✅.

### v1.8: Facebook long posts, whole-app block, daily time limit

**Facebook false block (from phone logs):** scrolling inside one long post on the home feed blocked as "Reels". The feed list (`items=15`, bounds 235–2151) showed a single visible row whose *screen* bounds filled the list exactly (`ViewGroup@235-2151`), because screen bounds are clipped to the list, so it passed the pager shape test. Every scroll (`dy` −77…−449, index stuck at 1) then counted as a swipe.

| Change | Detail |
|---|---|
| Real page height | `NodeTree.isTallerThan`: a page whose unclipped height (`getBoundsInParent`) is over 108% of the pager is a long post, not a Short/Reel. Applied in `isFullScreenPager` and `isPagerMidSwipe`. Logged as `(h=…)` in `player:` lines |
| Facebook needs evidence | `AppDetector.swipeByDistance = false` for Facebook: a scroll distance alone doesn't block; a new item index, the next page sliding in, or a page-text change does. (Real Reels log shows index 1→2 on the swipe.) |
| **Block {app} entirely** (per app, off by default) | Covers the app on every screen as soon as it opens; only LEAVE APP is offered (`BlockMessage.leaveAppOnly`) |
| **Daily time limit** (per app, off by default) | 15 min – 12 h in 15-min steps (default 1 h when switched on). The service counts time while a guarded app is in front and the screen is on (5 s ticks, `usage_<app>` in prefs, reset each day). At the limit: "TIME'S UP" block with LEAVE APP only, until tomorrow. "Used today" is shown under the switch |

Harness: *Block YouTube entirely* → test app covered on open, LEAVE APP → launcher ✅; limit 15 min with 14:50 used → blocked ~10 s after opening ✅; both off → Short swipe still blocked ✅.

### v1.9: TikTok

TikTok's video feed (For You, Following, and any video opened from a link, search or profile) is one vertical swipe-to-next player, so it uses the Shorts / Reels engine unchanged: the first video plays, swiping on is blocked, *Back to the video* keeps the blocked one.

| Part | Detail |
|---|---|
| Packages | `com.zhiliaoapp.musically` (global), `com.ss.android.ugc.trill` (some regions), `com.zhiliaoapp.musically.go` (Lite). `GuardedApp` now holds several packages; all are in `guard_service.xml` and the manifest `<queries>` |
| `tiktok/TikTokDetector.kt` | Shape test (`isFullScreenPager`) on scrollable views, known ids (`TikTokRules.feedIds`), or ViewPager / RecyclerView classes (TikTok's pager may not be marked scrollable). Not on every view: a window's root layout also has one full-size child |
| Rules | **Block swiping to the next video** (on), **Block the video player entirely** (off). Whole-app block and daily time limit come from v1.8 |
| Left alone | Profiles, search results, inbox and messages (grids and lists don't pass the shape test); comment sheets (not the pager) |

Not tested on real TikTok (not installed on the emulator). The ids are guesses; detection relies on shape. First phone logs will tell whether the pager is found (`clues TikTok … pager-shape:…`) and whether swipes come as scroll events or only as page-text changes.

`app` also accepts `-PaltBuildDir=build-alt` now.

Known: behind a whole-app block the app keeps running, so a playing video's sound may continue until LEAVE APP is tapped. After installing an APK over the old one, Android may stop the guard; switch it off and on again in Accessibility settings.

**Diagnostics from a phone without a PC:** turn on Diagnostics, tap CLEAR, reproduce the problem, come back and tap COPY LOG. The copied text includes the guard's version, the phone model, the YouTube / Instagram / Facebook versions and the last 300 events.

Instagram and Facebook are not installed on the emulator, so their detection is untested; the ids and descriptions in `instagram/InstagramRules.kt` and `facebook/FacebookRules.kt` are best guesses.

**Calibrating on a phone:** install the APK, turn the guard and *Diagnostics* on, then with the phone connected by USB run `adb logcat -s SMG` while you use each app (open a video and scroll under it, swipe Shorts / Reels, scroll Explore and the Facebook Video tab). The `clues ...` and `scroll ...` lines show what the app reports; put them into that app's `*Rules.kt` file.
