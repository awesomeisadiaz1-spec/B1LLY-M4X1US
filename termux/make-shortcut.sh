#!/data/data/com.termux/files/usr/bin/bash
# Creates a Termux:Widget home-screen shortcut that starts B1LLY with one tap.
DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p ~/.shortcuts
cat > ~/.shortcuts/B1LLY <<EOS
#!/data/data/com.termux/files/usr/bin/bash
exec bash "$DIR/start.sh"
EOS
chmod +x ~/.shortcuts/B1LLY "$DIR"/start.sh "$DIR"/stop.sh
echo "Shortcut created. Add the Termux:Widget to your home screen and tap B1LLY."
