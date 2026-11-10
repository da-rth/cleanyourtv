#!/usr/bin/env bash
# Build this branch's launcher, install it on a TV, and push the hero look to it.
# Run from anywhere inside a checkout of this branch. The TV must already be debloated and reachable over ADB
# (see hero-launcher/PROMPT.md, or PROMPT.md at the repo root for the connection and debloat steps).
#
#   TV_HOST=192.168.1.50 hero-launcher/apply.sh
#
# Optional environment:
#   TMDB_KEY=<v3 key>      full-screen hero details from TMDB. Without it the script asks (and offers to save it in the
#                          gitignored hero-launcher/.tmdb-key); skip it and the hero shows each app's own artwork and title only
#   DOCK_APPS=a,b,c        package names for the dock, in order. Without it the script asks the first time and saves the
#                          answer in hero-launcher/local/profile.json (gitignored)
#   VPN_APP=<package>      the VPN app the lock pill opens (blank: the lock asks on the TV the first time)
#   UI_SCALE=<50..125>     interface size percent (default: the theme's 75)
#   STOCK_LAUNCHER=<pkg>   the TV's own launcher to disable, as launcher/build-and-install.sh takes it ("none" keeps it)
#   SKIP_BUILD=1           install the APK built earlier
#
# Everything specific to one TV or one person (dock apps, VPN app, Live TV package, remote-button mappings, tile pictures)
# lives in hero-launcher/local/ and is never committed: profile.json for your choices and device.json for the TV's own values
# (same keys as a theme's config.json, see themes/README.md). Both are optional.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the IP address of the TV}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

PY=tools/venv/bin/python
if [ ! -x "$PY" ]; then
  python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt
fi

# 1. the theme: look + this TV's local device file + your local profile + overrides, merged into a scratch folder
#    (the TMDB key and your choices never touch the repo)
LOCAL="$HERE/local"; mkdir -p "$LOCAL"
ASK=0; [ -t 0 ] && ASK=1

KEY="${TMDB_KEY:-}"
[ -z "$KEY" ] && [ -f "$HERE/.tmdb-key" ] && KEY="$(tr -d '[:space:]' < "$HERE/.tmdb-key")"
if [ -z "$KEY" ] && [ "$ASK" = 1 ]; then
  echo
  echo "The hero (full-screen backdrop, rating, genres and description for what you are watching) needs a TMDB API key."
  echo "Get a free v3 key at https://www.themoviedb.org/settings/api. Without one the hero shows each app's own"
  echo "artwork and title only."
  read -r -s -p "TMDB v3 API key (Enter to skip): " KEY; echo
  if [ -n "$KEY" ]; then
    read -r -p "Save it on this computer in hero-launcher/.tmdb-key (gitignored) for next time? [Y/n] " yn
    case "${yn:-y}" in [Nn]*) ;; *) umask 077; printf '%s\n' "$KEY" > "$HERE/.tmdb-key" ;; esac
  fi
fi

if [ -z "${DOCK_APPS+x}" ] && [ ! -f "$LOCAL/profile.json" ] && [ "$ASK" = 1 ]; then
  echo
  echo "Which apps go in the dock, in order? Package names separated by commas, for example"
  echo "  com.netflix.ninja,com.amazon.amazonvideo.livingroom   (see them: tools/tv.py 'pm list packages -3')"
  read -r -p "Dock apps (Enter for the launcher's own defaults): " DOCK_APPS
  if [ -z "${VPN_APP+x}" ]; then
    read -r -p "VPN app package for the lock pill (Enter to choose on the TV the first time): " VPN_APP
  fi
  DA="$DOCK_APPS" VA="${VPN_APP:-}" python3 - "$LOCAL/profile.json" <<'PY'
import json, os, sys
d = {}
if os.environ["DA"]: d["dock_apps"] = [p.strip() for p in os.environ["DA"].split(",") if p.strip()]
if os.environ["VA"]: d["vpn_app"] = os.environ["VA"].strip()
json.dump(d, open(sys.argv[1], "w"), indent=2)
PY
  unset DOCK_APPS VPN_APP
fi

WORK="$HERE/.work/theme"
rm -rf "$WORK" && mkdir -p "$WORK"
python3 - "$HERE" "$WORK/config.json" "$KEY" <<'PY'
import json, os, sys
here, out, key = sys.argv[1:4]
cfg = json.load(open(f"{here}/theme/look.json"))
def merge(path):
    if os.path.isfile(path):
        cfg.update({k: v for k, v in json.load(open(path)).items() if not k.startswith("_")})
merge(f"{here}/local/device.json")
merge(f"{here}/local/profile.json")
if os.environ.get("DOCK_APPS"): cfg["dock_apps"] = [p for p in os.environ["DOCK_APPS"].split(",") if p]
if "VPN_APP" in os.environ: cfg["vpn_app"] = os.environ["VPN_APP"]
if os.environ.get("UI_SCALE"): cfg["ui_scale_percent"] = int(os.environ["UI_SCALE"])
if key: cfg["tmdb_api_key"] = key
json.dump(cfg, open(out, "w"), indent=2)
print("theme keys:", ", ".join(sorted(k for k in cfg if k != "tmdb_api_key")))
PY
[ -z "$KEY" ] && echo "note: no TMDB key, so the hero shows each app's own artwork and title only"

# 2. the launcher; INPUT_KEY_SERVICE turns on the helper the remote's app buttons need on Google TV
export TV_HOST INPUT_KEY_SERVICE=1
( cd launcher && ./build-and-install.sh )

# 3. permissions the launcher asks for but Android only grants from a shell on a TV
"$PY" -I tools/tv.py "pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS; appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow; echo granted" | tail -1

# 4. the look
tools/theme.sh "$WORK"
echo
echo "Done. Press Home. Settings pill > Launcher settings, VPN privacy dot, Android settings."
echo "Close network ADB when you are finished:  tools/adb-closed.py $TV_HOST"
