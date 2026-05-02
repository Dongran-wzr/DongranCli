#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$HOME/bin"
JAR="$SCRIPT_DIR/target/DongranCli-1.0-SNAPSHOT.jar"

echo "[1/4] Checking jar..."
if [[ ! -f "$JAR" ]]; then
  echo "Jar not found: $JAR"
  echo "Build first: mvn clean package"
  exit 1
fi

echo "[2/4] Creating bin dir..."
mkdir -p "$BIN_DIR"

echo "[3/4] Writing launchers..."
cat > "$BIN_DIR/dongran" <<EOF
#!/usr/bin/env bash
java -jar "$JAR" "\$@"
EOF

cat > "$BIN_DIR/dongrancli" <<EOF
#!/usr/bin/env bash
java -jar "$JAR" "\$@"
EOF

chmod +x "$BIN_DIR/dongran" "$BIN_DIR/dongrancli"

echo "[4/4] Updating PATH..."
SHELL_RC="$HOME/.bashrc"
if [[ -n "${ZSH_VERSION:-}" ]] || [[ "${SHELL:-}" == *"zsh"* ]]; then
  SHELL_RC="$HOME/.zshrc"
fi

if ! grep -q 'export PATH="$HOME/bin:$PATH"' "$SHELL_RC" 2>/dev/null; then
  echo 'export PATH="$HOME/bin:$PATH"' >> "$SHELL_RC"
  echo "PATH entry added to $SHELL_RC"
else
  echo "PATH entry already exists in $SHELL_RC"
fi

echo "Done. Reopen terminal, then run: dongran"
