#!/usr/bin/env bash
# Copy the local data exports to the droplet and import them into its MySQL.
#
#   scripts/import-to-server.sh root@<droplet-ip> [db-host] [db-user] [db-name]
#
# Defaults match docker-compose.yml: 10.122.0.4, nepbrain, nepbrain_nepse_analysis.
# The exports are data only (INSERT IGNORE, no CREATE TABLE), so start the app on the droplet once first:
# Flyway creates the tables. Import before opening the analysis pages, so backtest run ids don't collide.
# Re-running is safe: rows that already exist are skipped.
set -euo pipefail

TARGET=${1:?usage: $0 user@droplet [db-host] [db-user] [db-name]}
DB_HOST=${2:-10.122.0.4}
DB_USER=${3:-nepbrain}
DB_NAME=${4:-nepbrain_nepse_analysis}
cd "$(dirname "$0")/.."

FILES=(data/export/market_history_*.sql data/export/backtest_runs_*.sql)
for f in "${FILES[@]}"; do [ -f "$f" ] || { echo "missing $f"; exit 1; }; done

echo "== compressing and copying ${#FILES[@]} files to $TARGET:/tmp/nepse-import/"
TMP=$(mktemp -d)
for f in "${FILES[@]}"; do gzip -c "$f" > "$TMP/$(basename "$f").gz"; done
ssh "$TARGET" 'mkdir -p /tmp/nepse-import'
scp "$TMP"/*.gz "$TARGET":/tmp/nepse-import/
rm -rf "$TMP"

echo "== importing on the droplet into $DB_USER@$DB_HOST/$DB_NAME"
# market history first (prices, stocks, rankings), then backtest runs. The MySQL client runs in a throwaway container,
# so nothing needs installing on the droplet.
ssh -t "$TARGET" "DB_HOST='$DB_HOST' DB_USER='$DB_USER' DB_NAME='$DB_NAME' bash -s" <<'REMOTE'
set -euo pipefail
read -rsp "MySQL password for $DB_USER@$DB_HOST: " DB_PASS; echo
mysql() { docker run --rm -i --network host -e MYSQL_PWD="$DB_PASS" mysql:8.4 mysql -h "$DB_HOST" -u "$DB_USER" "$@"; }

tables=$(mysql -N "$DB_NAME" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('daily_price','backtest_run')")
if [ "$tables" != "2" ]; then
  echo "Tables are missing in $DB_NAME. Start the app once (docker compose up -d --build), wait until it is healthy, then re-run."
  exit 1
fi
for f in /tmp/nepse-import/market_history_*.sql.gz /tmp/nepse-import/backtest_runs_*.sql.gz; do
  echo "   $(basename "$f")"
  gunzip -c "$f" | mysql --default-character-set=utf8mb4 "$DB_NAME"
done
mysql -t "$DB_NAME" -e "
  SELECT (SELECT COUNT(*) FROM trading_day) sessions, (SELECT MIN(trade_date) FROM trading_day) first_day,
         (SELECT MAX(trade_date) FROM trading_day) last_day, (SELECT COUNT(*) FROM daily_price) price_rows,
         (SELECT COUNT(*) FROM stock) stocks, (SELECT COUNT(*) FROM backtest_run) backtest_runs, (SELECT COUNT(*) FROM backtest_trade) backtest_trades"
rm -rf /tmp/nepse-import
echo "Done. Restart the app so it reloads the history: docker compose restart app"
REMOTE
