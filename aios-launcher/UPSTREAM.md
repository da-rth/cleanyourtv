# What could go upstream

The upstream guide ([CONTRIBUTING.md](../CONTRIBUTING.md)) keeps `launcher/` small: no network, no background services, no
polling, an APK under 576 KB. This launcher breaks all of those on purpose, so the launcher changes stay in this fork. What fits
upstream, and where:

| Piece | Home upstream | Notes |
|---|---|---|
| A debloat list for a TV you have lived with for a few days | `examples/<brand>-<model>/` (see `docs/workflows/tv-list.md`) | Nothing like that is kept in this fork, by choice: device lists stay out of it. Add one to a branch off upstream `main`, not off this branch. |
| A row on SHOWCASE.md ("Forks of the launcher") | `SHOWCASE.md`, one row linking this fork | Row text below. The page warns readers about `INTERNET` and similar; this fork's README lists its permissions. |
| `ui_scale_percent`, `dock_corner_radius_dp`, `pills_expand_on_focus` | a small PR each, off by default, following `docs/workflows/launcher-switch.md` | No network, no service; each needs a screenshot from a real TV. |
| Left from the library pill returns to the last app | a small fix PR | One handler in `AppAdapter`. |
| Hide/show an app list, `app_library` labels | already upstream (app library) | Only wording changed here. |
| Continue watching shelf and the TMDB hero | does not fit: it needs `INTERNET` and loads posters | `docs/watch-next.md` upstream already documents reading Watch Next without the network, so a no-poster, text card version could be a PR. |
| VPN privacy dot | could be an add-on in `addons/` (it is its own small app's worth of code) | Needs a foreground service and the overlay permission, so it would need the add-on rules in `addons/README.md`. |

Row for SHOWCASE.md (Forks of the launcher table): name, what it is, built on (fill in the TV you tested on), by:

> [AIOStreams launcher](https://github.com/da-rth/cleanyourtv/tree/aios-launcher/aios-launcher) | A fork of the launcher with a Continue
> watching home screen: titles from Netflix, Prime Video, AIOStreams and others (Android TV's Watch Next), a full-screen TMDB hero with
> season and episode, apps one press of Down away, a settings dialog and an optional VPN privacy dot. Network: TMDB and the apps'
> own poster URLs only while the shelf loads; also asks for READ_TV_LISTINGS, "Display over other apps" and a foreground service for
> the dot | <your TV model>, <its Android or Google TV version> | [da-rth](https://github.com/da-rth)

## Checks in this fork

- The test suite passes except `tests/test_card_picture_staging.py`, which fails the same way on untouched upstream `main`.
- Five new theme keys (`continue_watching`, `tmdb_api_key`, `ui_scale_percent`, `dock_corner_radius_dp`,
  `pills_expand_on_focus`) are listed in `SCREENSHOT_WANTED` in `tests/test_switches.py` with a "fork only" comment. Upstream
  requires a real-TV screenshot per key in `docs/switches.md`; remove a key from that list when you add its screenshot, which is
  also the first step of sending any of them upstream.
- `tests/test_card_spec_reserved.py` and `docs/specs/card.md` follow the app library pill's new label, "More apps".
- The Hebrew home layout (`layout-iw`) carries the new views so its view ids match the default layout; the shelf and hero were
  not laid out or tested in Hebrew.
