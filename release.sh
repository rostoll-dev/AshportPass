#!/data/data/com.termux/files/usr/bin/bash
set -e

VERSION=$1
NOTES=${2:-"New Ashport Pass update."}
GH_USER="rostoll-dev"

if [ -z "$VERSION" ]; then
  echo "Usage: ./release.sh <version> \"<release notes>\""
  echo "Example: ./release.sh 1.0.5 \"5-min background heartbeat and exact alarms\""
  exit 1
fi

cd ~/ashport-pda

# Ensure remote URL and AutoUpdater point to rostoll-dev
git remote set-url origin "https://github.com/${GH_USER}/AshportPass.git"
sed -i "s/GITHUB_REPO = \".*\"/GITHUB_REPO = \"${GH_USER}\/AshportPass\"/" app/src/main/java/com/rostoll/ashportpda/AutoUpdater.java

# Auto-increment versionCode and update versionName in app/build.gradle
CURRENT_CODE=$(grep -oP 'versionCode \K[0-9]+' app/build.gradle || echo "1")
NEXT_CODE=$((CURRENT_CODE + 1))
sed -i "s/versionCode [0-9]*/versionCode $NEXT_CODE/" app/build.gradle
sed -i "s/versionName \".*\"/versionName \"$VERSION\"/" app/build.gradle

echo "🔨 Compiling signed AshportPass v$VERSION (versionCode $NEXT_CODE)..."
./gradlew assembleRelease --no-daemon

APK_PATH="app/build/outputs/apk/release/app-release.apk"
cp "$APK_PATH" "/sdcard/Download/AshportPass.apk"

git add .
git commit -m "Release v$VERSION" || true
git push -u origin main

echo "🚀 Uploading AshportPass.apk to GitHub Release v$VERSION..."
gh release create "v$VERSION" "/sdcard/Download/AshportPass.apk#AshportPass.apk" \
  --repo "${GH_USER}/AshportPass" \
  --title "Ashport Pass v$VERSION" \
  --notes "$NOTES"

echo "✅ Done! Permanent download link:"
echo "https://github.com/${GH_USER}/AshportPass/releases/latest/download/AshportPass.apk"
