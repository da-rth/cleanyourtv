# AIOStreams launcher

A fork of the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) reference launcher (`launcher/`) with a Continue
watching home screen: what you were watching in Netflix, Prime Video, AIOStreams, Stremio and others, with a full-screen hero
of the focused title (TMDB backdrop, rating, genres, season and episode, description), then your apps one press of Down away.
Tested on one Google TV 12 set (see Notes). It lives on the `aios-launcher` branch; `main` follows upstream.

To put it on a TV: [`PROMPT.md`](PROMPT.md) (a prompt for a coding assistant) or
`TV_HOST=<ip> aios-launcher/apply.sh` once the TV is debloated.

## What it adds to the upstream launcher

- **Continue watching shelf** from Android TV's Watch Next rows (`READ_TV_LISTINGS`): cards with poster and progress bar
  (shown even while focused), OK opens the title in its app, hold OK removes a card (kept on the launcher's side; the title
  comes back if the app publishes it again later). Same size, spacing, focus animation, glass highlight and scrolling as the apps.
- **TMDB hero** behind it (`tmdb_api_key` in the theme, off without one): backdrop, title with the app's round icon,
  "Season 1 · Episode 9 · Boca", year, rating, length, genres and description. Metadata and images are loaded ahead of focus and
  cached on disk (0–100 MB, 50 by default); the hero text does not move between the shelf and the apps.
- **Apps row**, tucked away until Down ("Apps" label); the shelf hides while apps show and returns on Up or Back.
  Uses the built-in app library as **More apps**: hold OK on a tile for Hide app, and re-add from More apps.
- **Pills**: transparent, icon only until focused (label expands), a vignette while they are focused; the VPN lock is the first
  pill (green connected, red not): OK opens your VPN app (it asks the first time, hold OK to change).
- **Settings pill → dialog**: Launcher settings (shelf, hero, hide apps until Down, pill labels, interface size
  50/75/100/125 %, image cache size, restore removed titles, clear cache), **VPN privacy dot**, Android settings. Every value
  applies at once; Left/Right step values. Choices are saved on the TV and win over the theme.
- **VPN privacy dot**: a dot, globe or lock over every app except Home (green connected, orange connecting, red disconnected),
  any corner, size and offset in px, optional fade (clock driven, so it works with animations off). Off until enabled. Needs
  "Display over other apps" and runs as a foreground service.
- Smaller: `ui_scale_percent`, `dock_corner_radius_dp`, `pills_expand_on_focus`, `continue_watching` switches; long-press
  Force stop and Uninstall in the tile menu.

## Files

| Path | What |
|---|---|
| `theme/look.json` | the look (dock row, columns, pills, scale, shelf on). Contains no device or personal values |
| `apply.sh` | asks for what it needs (TMDB key, dock apps, VPN app), builds, installs, grants, and pushes the merged theme |
| `PROMPT.md` | the prompt for another TV |
| `UPSTREAM.md` | which parts could go to the upstream repo and which cannot |
| `local/` (gitignored) | `profile.json` (your dock apps, VPN app) and `device.json` (this TV's Live TV package, hidden apps, remote-button mappings, tile pictures), same keys as a theme's `config.json`. Never committed |
| `.tmdb-key` (gitignored) | your TMDB key, if you chose to save it |

**Nothing specific to a TV or a person is stored in this repository.** The values for a set are asked for the first time and
kept on your computer in `local/`; the theme merges theme → `local/device.json` → `local/profile.json` → environment.

## Notes

- **Secrets.** `apply.sh` asks for the TMDB key (hidden input) unless `TMDB_KEY` or `.tmdb-key` has it, and writes it into the
  theme file on the TV in plain text. Nothing in the repo holds it.
- **Permissions beyond upstream:** `INTERNET` (TMDB and poster downloads, only while the shelf loads), `READ_TV_LISTINGS`,
  `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE` (+ special use), `KILL_BACKGROUND_PROCESSES`, `REQUEST_DELETE_PACKAGES`.
  Read the manifest before installing on a TV that is not yours.
- **Size.** About 600 KB against upstream's 576 KB limit.
- **Tested** on a single Sony Google TV 12 set: Home, shelf, hero, apps, More apps, both settings dialogs, the privacy dot (dot and globe, fade, size,
  position), the YouTube/Netflix/Input buttons and volume repeat. **Not tested:** hiding and re-adding an app, the VPN app picker's
  list, the dot's red and orange states, the lock style on screen, the interface-size restart, other TV models.
