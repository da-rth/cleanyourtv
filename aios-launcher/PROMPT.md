# Prompt: put my launcher on another TV

Paste the block below into Claude Code (or another assistant with a shell) on a computer on the same Wi-Fi as the TV.
It works on Android TV / Google TV sets, not Samsung Tizen, LG webOS or Fire TV.

On the TV first: Settings → Device Preferences → About → press OK on "Build" 7 times, then Developer options → USB
debugging (and Network/ADB debugging or Wireless debugging if it is offered). Keep the TV on, and plan to finish in one
sitting: some boxes cut power to USB ports while debugging is on.

You will want: a TMDB v3 API key if you want the full-screen hero with ratings and descriptions (free at
themoviedb.org → Settings → API). Without one the hero shows each app's own artwork and title.

---

```
Set up my AIOStreams launcher on this Android TV. My TV model is: <MODEL NAME HERE>.
My TMDB v3 key (optional; leave blank to skip): <KEY or blank>

Work on the real device, step by step, and check what you do with screenshots pulled from the TV. Ask me before
anything that depends on my preferences. Never print, log or commit my TMDB key.

0. Get the code
   git clone -b aios-launcher https://github.com/da-rth/cleanyourtv && cd cleanyourtv
   Read PROMPT.md at the repo root and follow its Connect, Back up first, Debloat and Verify sections exactly (ADB
   connection tricks, the backup and restore script, the packages never to disable, small batches with a screenshot between
   them, closing network ADB at the end). Then read aios-launcher/README.md. Use the repo's tools/ scripts, not your own.

1. Connect, back up, debloat (from the root PROMPT.md)
   - Start from the closest list in examples/ for this TV (same device name and firmware). If there is none, build one by the
     root prompt's rules, and check the TV's SDK first: tools/tv.py 'getprop ro.build.version.sdk' must be 26 or more.
   - Ask me, as one checklist, which of these I use before disabling them: streaming apps, voice assistant, Chromecast,
     AirPlay/DLNA, Ambient mode, timers, firmware updates. Disable only what I do not tick.
   - If I want a third-party YouTube client instead of the system YouTube app, say that disabling the system app
     stops casting from a phone to it, and ask.

2. Collect what is specific to this TV and to me (ask me; keep it local)
   - Nothing about a particular TV or person goes in the repository. Keep it in aios-launcher/local/ (gitignored): write
     profile.json for my choices and, only if this TV needs it, device.json for the TV's own values. Both use the same keys as a
     theme's config.json (themes/README.md lists them).
   - Ask me for: my dock apps (up to 6, in order), my VPN app (optional, the lock pill asks on the TV the first time if it is
     empty), and my TMDB key (see the top: explain that without it the hero is plain, and do not skip asking).
   - device.json, only where the TV differs from a normal one, with:
       live_tv_package   the TV app/tuner package that Inputs should open (leave it out if the TV has no tuner)
       hidden_apps       that same tuner app, so it does not show as a tile
       app_keys          remote buttons that opened the stock launcher's apps; read the real keys with tools/tv.py
                         'getevent -lt' while I press them (a scancode entry looks like scan:<code>:<package>)
       app_banners       "package=drawable" entries for an app that needs another tile picture
     Ask me before mapping any button or replacing an app: for example, whether a YouTube client should replace the TV's
     own YouTube app and take over the remote's YouTube button.

3. Install the launcher with the script, not by hand
   - Needs JDK 17, Android SDK platform 34 and build-tools 34.0.0 (or Docker: tools/docker-build.sh launcher, then SKIP_BUILD=1).
   - Run: TV_HOST=<tv ip> TMDB_KEY=<the key I gave you> aios-launcher/apply.sh   (leave TMDB_KEY out if I skipped it; the
     script also asks, with hidden input, when run from a terminal)
     It builds this branch's launcher, installs it, makes it Home (disabling the stock launcher only once Home is confirmed),
     grants READ_TV_LISTINGS and "Display over other apps", and pushes the theme. Set STOCK_LAUNCHER=<package> if the TV's own
     launcher is not one it recognises, and look at docs/TROUBLESHOOTING.md for setupwraith on Google TV.
   - This launcher's APK is about 600 KB, over upstream's 576 KB CI limit. That is expected for this branch.

4. Verify on the TV, then ask me to press the real remote
   - Home returns to the launcher; Down shows the Apps row with its "Apps" label and hides Continue watching; Up or Back
     goes back; the last tile leads to "More apps"; Left from it returns to the apps; the Settings pill opens a dialog with
     Launcher settings, VPN privacy dot and Android settings, and Left/Right change values.
   - Ask me to press: Home, each app button on the remote (Netflix, YouTube, Prime...), Input, and to hold volume up. If a
     button does nothing, add it to the device file's app_keys. If holding volume up stops repeating, tell me: the launcher's
     key services can cost that on some Google TV builds.
   - Screenshots can come back blank while another app with a protected overlay (a VPN dot app, for one) is running. Stop
     that app for the screenshot, or use tools/tv.py 'uiautomator dump' instead. Also check "settings get global
     animator_duration_scale": if it is 0, Android animations jump to their end on this TV, which is not a launcher bug.
   - Do not send key presses while I am holding the remote: dialog values are saved, so stray keys change my settings.

5. Finish
   - Turn network ADB off, reboot, and run tools/adb-closed.py <tv ip> (the root prompt's closing steps).
   - Write me a short README of what you did for this TV, and a restore command (tools/restore.sh with the list you used).
   - Do not commit or push anything that is specific to this TV or to me (aios-launcher/local/, package choices, remote
     mappings, the key, screenshots of my watch history). If you found a general bug in the launcher or this script, tell me
     and offer a patch for my fork (da-rth/cleanyourtv, branch aios-launcher), and wait for my yes before pushing.
```

---

Updating later: `git pull`, then run `aios-launcher/apply.sh` again. The launcher's settings (Settings pill) are kept on the
TV and survive a reinstall; the theme is pushed again each time.
