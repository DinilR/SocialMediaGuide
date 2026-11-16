# How SocialMediaGuide Works

SocialMediaGuide never looks at pixels and never touches the other apps. It reads the structure of whatever is on screen and watches the events Android sends about it. Most of that logic is shared by all three apps. Only a thin layer is specific to each app.

## 1. Where it gets its information

The app runs as an **Accessibility Service**, the same Android feature screen readers use. Once you enable it, Android gives it two things for YouTube, Instagram and Facebook only:

- **Events.** Short messages like "window changed", "content changed" or "this list scrolled by 340 pixels". Scroll events say how far the list moved.
- **The view tree.** A description of everything on screen: each element's type (list, pager, text), its ID name if the app gave it one (`reel_recycler`, `clips_viewer_view_pager`), its size and position, whether it can scroll, and its text.

When it decides to block, it draws a full-screen **overlay** on top of the app ([BlockOverlay.kt](app/src/main/java/com/socialmediaguide/service/BlockOverlay.kt)). The app underneath keeps running, but you can't touch it.

## 2. The decision loop

Each time an event arrives, [GuardService.kt](app/src/main/java/com/socialmediaguide/service/GuardService.kt) runs this loop:

```
event → which app is it? → that app's detector classifies the screen → apply the rule → block or not
```

The detector puts the screen into one of three types:

| Screen type | Example | What gets blocked |
|---|---|---|
| **Other** | Home feed, search, profile, messages | Nothing |
| **Feed** | A normal video page with recommendations below it | Scrolling the list under the video |
| **Pager** | Shorts, Reels | Swiping to the next item |

The home feed counts as Other, so you can scroll it and open things. You get blocked only after you're inside a video, Short or Reel and try to keep going.

## 3. How it detects scrolling (video pages)

This is the simpler case. The list of recommendations under a video is an ordinary scrollable list, and Android sends a **scroll event** whenever it moves. The guard:

1. Finds the player at the top of the screen and the list below it.
2. Waits for a scroll event from that list that moves downward.
3. Blocks, covering the area below the player.

## 4. How it detects moving to the next Short or Reel

This is the hard part, and it works in two steps.

**Step A: recognise that you're in a Shorts or Reels player.** There are two independent checks, and either one is enough:

- **Known IDs.** For example, YouTube's `reel_recycler`, Instagram's `clips_viewer_view_pager`, or a selected Reels tab.
- **Shape.** See `isFullScreenPager` in [NodeTree.kt](app/src/main/java/com/socialmediaguide/guard/NodeTree.kt). The screen must contain a vertical element at least 75% of the screen's height, where a single page fills almost all of it, and that page must not contain a feed list. Every Shorts or Reels player looks like this, even with its IDs removed. The "no feed inside" condition matters: Facebook's and YouTube's home tabs have the same tall shape, but the page inside is a scrolling feed. Missing that condition caused the earlier false blocks on Facebook.

**Step B: notice that the item changed.** This uses two signals, because the apps behave differently:

- **Scroll events from the pager.** Instagram's player sends a scroll event when you swipe, so a swipe there is caught directly. Facebook sends them some of the time.
- **Page fingerprint.** This is the backup, and the only option for YouTube, whose Shorts player sends **no scroll events at all**. When a Short settles, the guard records the texts on it: channel name, title, caption, audio name. Counts like "1.2M" are ignored because they change on their own. Every 0.8 s it reads them again. If the main texts have disappeared and different ones have appeared, a new Short is on screen. It then waits 350 ms and checks again, so a half-finished swipe doesn't trigger a block.

There's also a short **settle time**. The first Short you open is never blocked: it becomes the starting point. Only a change after that counts as "moved to the next one". "Back to the Short" simply resets this starting point to the Short that was blocked.

## 5. Same recognition in each app, or different?

**Mostly the same.** The overall loop, the shape test, the fingerprint, the overlay and the rules are shared code. Each app has a small detector file that adds what's specific to it:

| | YouTube | Instagram | Facebook |
|---|---|---|---|
| Player found by | IDs `reel_recycler` / `reel_player_page_container`, plus shape | ID `clips_viewer_view_pager` or Reels tab, plus shape | Shape only (the player has no ID name) |
| Swipe detected by | **Fingerprint only** (no scroll events) | Scroll events, plus fingerprint | Scroll events, plus fingerprint |
| Special care | Don't treat the home screen (`youtube_logo`, `tabs_bar`) as Shorts | Little | Don't confuse the home feed tab with Reels |

Files: [YouTubeDetector.kt](app/src/main/java/com/socialmediaguide/youtube/YouTubeDetector.kt), [InstagramDetector.kt](app/src/main/java/com/socialmediaguide/instagram/InstagramDetector.kt), [FacebookDetector.kt](app/src/main/java/com/socialmediaguide/facebook/FacebookDetector.kt).

So the approach is the same everywhere, with different clues per app. YouTube depends most on the fingerprint, and Facebook depends most on the shape test.

## 6. Limitations

- **App updates can break it.** If YouTube renames `reel_recycler` or changes its layout, detection may fail until the detector is updated. The shape test reduces that risk because it doesn't rely on names. This is also why the COPY LOG button records the app versions.
- **The fingerprint needs text on screen.** A Short with no caption, title or channel text visible would be hard to tell apart from the next one.
- **Slight delay.** A YouTube swipe is noticed within about 1 second (the 0.8 s check plus the 350 ms confirmation), so you may glimpse the next Short before the block screen appears.

Full design notes are in [DESIGN.md](DESIGN.md).
