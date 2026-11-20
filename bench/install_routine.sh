#!/usr/bin/env bash
# Installe la routine 48 h comme agent launchd (toutes les heures, et au démarrage de la session).
#   bench/install_routine.sh            écrit le plist et le charge (launchctl bootstrap)
#   bench/install_routine.sh --dry-run  affiche le plist sans rien écrire ni charger
#   bench/install_routine.sh --uninstall  décharge et supprime le plist
# Le dépôt cible est celui qui contient ce script.
set -euo pipefail

LABEL="local.dev-model-reports.routine"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/dev-model-reports.log"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="$(command -v python3 || echo /usr/bin/python3)"
DOMAIN="gui/$(id -u)"

xml() { local s="$1"; s="${s//&/&amp;}"; s="${s//</&lt;}"; s="${s//>/&gt;}"; printf '%s' "$s"; }

plist() {
  local path="/opt/homebrew/bin:$HOME/.local/bin:$HOME/.sdkman/candidates/java/current/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
  cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml "$PYTHON")</string>
    <string>$(xml "$REPO/bench/routine.py")</string>
    <string>tick</string>
  </array>
  <key>WorkingDirectory</key><string>$(xml "$REPO")</string>
  <key>StartInterval</key><integer>3600</integer>
  <key>RunAtLoad</key><true/>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$(xml "$path")</string>
    <key>JAVA_HOME</key><string>$(xml "$HOME/.sdkman/candidates/java/current")</string>
    <key>LANG</key><string>fr_FR.UTF-8</string>
PLIST
  # Réseau de la machine (proxy, autorité de certification) : sans eux, les outils ne joignent pas leur API.
  local var
  for var in HTTPS_PROXY HTTP_PROXY NO_PROXY NODE_EXTRA_CA_CERTS SSL_CERT_FILE; do
    if [ -n "${!var:-}" ]; then
      printf '    <key>%s</key><string>%s</string>\n' "$var" "$(xml "${!var}")"
    fi
  done
  cat <<PLIST
  </dict>
  <key>StandardOutPath</key><string>$(xml "$LOG")</string>
  <key>StandardErrorPath</key><string>$(xml "$LOG")</string>
</dict>
</plist>
PLIST
}

case "${1:-}" in
  --dry-run)
    plist
    ;;
  --uninstall)
    launchctl bootout "$DOMAIN" "$PLIST" 2>/dev/null || true
    rm -f "$PLIST"
    echo "routine désinstallée ($PLIST supprimé)"
    ;;
  "")
    mkdir -p "$(dirname "$PLIST")" "$(dirname "$LOG")"
    plist > "$PLIST"
    plutil -lint "$PLIST" >/dev/null
    launchctl bootout "$DOMAIN" "$PLIST" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$PLIST"
    echo "routine installée : $PLIST (toutes les heures ; journal : $LOG)"
    ;;
  *)
    echo "usage : $0 [--dry-run | --uninstall]" >&2
    exit 2
    ;;
esac
