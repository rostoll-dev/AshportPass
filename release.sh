#!/data/data/com.termux/files/usr/bin/bash
set -e

VERSION=$1
NOTES=${2:-"New Ashport Pass update."}

if [ -z "$VERSION" ]; then
  echo "Usage: ./release.sh <version> \"<release notes>\""
  exit 1
fi

cd ~/ashport-pda

sed -i "s/versionName \".*\"/versionName \"$VERSION\"/" app/build.gradle

echo "🔨 Compiling signed AshportPass v$VERSION..."
./gradlew assembleRelease --no-daemon

APK_PATH="app/build/outputs/apk/release/app-release.apk"
cp "$APK_PATH" "/sdcard/Download/AshportPass.apk"

git add .
git commit -m "Release v$VERSION" || true
git push origin main

echo "🚀 Uploading AshportPass.apk to GitHub Release v$VERSION..."
gh release create "v$VERSION" "/sdcard/Download/AshportPass.apk#AshportPass.apk" \
  --title "Ashport Pass v$VERSION" \
  --notes "$NOTES"

echo "✅ Done! Permanent download link for anyone:"
echo "https://github.com/$(gh api user --jq .login)/AshportPass/releases/latest/download/AshportPass.apk"
