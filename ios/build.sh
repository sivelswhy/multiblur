#!/bin/bash
# Builds MultiBlur.ipa for iPhone and iPad with swiftc (no Xcode project). Needs Xcode for the iOS SDK.
# The app shares its engine (CoreML detection, masking, tracking, voices) with the macOS app.
#
# The .ipa is unsigned: install it with a sideloading tool (Sideloadly, AltStore…) that signs it with
# your Apple ID.
#
# Environment:
#   BUILD_NUMBER  sets the bundle version to 1.0.<BUILD_NUMBER> (used by CI releases)
#   SIMULATOR=1   builds for the iOS Simulator instead (build/MultiBlur-sim.app)
set -euo pipefail
cd "$(dirname "$0")"
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}

SHARED=(Analysis BlurEngine CutDetector FaceDetector FaceEmbedder Options Tracker Vocoder VoiceChanger)
MODELS=../models
if [ -n "${SIMULATOR:-}" ]; then
    SDK=iphonesimulator; TARGET=arm64-apple-ios17.0-simulator; APP=build/MultiBlur-sim.app
else
    SDK=iphoneos; TARGET=arm64-apple-ios17.0; APP=build/Payload/MultiBlur.app
fi

rm -rf "$APP"
mkdir -p "$APP"

echo "→ Compiling CoreML models"
swift ../macos/tools/compile_model.swift "$MODELS/license-plate-finetune-v1s.mlpackage" "$APP/PlateDetector.mlmodelc"
swift ../macos/tools/compile_model.swift "$MODELS/CenterFace.mlpackage" "$APP/CenterFace.mlmodelc"
swift ../macos/tools/compile_model.swift "$MODELS/SFace.mlpackage" "$APP/SFace.mlmodelc"

echo "→ Compiling Swift sources ($TARGET)"
xcrun --sdk "$SDK" swiftc -O -swift-version 5 -parse-as-library -target "$TARGET" \
    $(printf '../macos/Sources/%s.swift ' "${SHARED[@]}") Sources/*.swift -o "$APP/MultiBlur"

echo "→ Resources"
cp Info.plist "$APP/Info.plist"
[ -n "${SIMULATOR:-}" ] && plutil -replace CFBundleSupportedPlatforms -json '["iPhoneSimulator"]' "$APP/Info.plist"
if [ -n "${BUILD_NUMBER:-}" ]; then
    plutil -replace CFBundleShortVersionString -string "1.0.$BUILD_NUMBER" "$APP/Info.plist"
    plutil -replace CFBundleVersion -string "$BUILD_NUMBER" "$APP/Info.plist"
fi
# The SDK the app was built with, which iOS checks for some behaviors.
plutil -replace DTPlatformName -string "$SDK" "$APP/Info.plist"
plutil -replace DTSDKName -string "$SDK$(xcrun --sdk "$SDK" --show-sdk-version)" "$APP/Info.plist"
plutil -replace DTPlatformVersion -string "$(xcrun --sdk "$SDK" --show-sdk-version)" "$APP/Info.plist"

# App icon (asset catalog), merged into Info.plist.
xcrun actool Resources/Assets.xcassets --compile "$APP" --platform "$SDK" --minimum-deployment-target 17.0 \
    --app-icon AppIcon --target-device iphone --target-device ipad \
    --output-partial-info-plist build/icon-info.plist > /dev/null
/usr/libexec/PlistBuddy -c "Merge build/icon-info.plist" "$APP/Info.plist" > /dev/null

# Translations: the engine's strings come from the macOS app, the iPhone's own are appended.
for lang in en fr; do
    mkdir -p "$APP/$lang.lproj"
    cp Resources/$lang.lproj/InfoPlist.strings "$APP/$lang.lproj/"
done
cat ../macos/Resources/fr.lproj/Localizable.strings Resources/fr.lproj/Localizable.strings > "$APP/fr.lproj/Localizable.strings"
plutil -lint -s "$APP/fr.lproj/Localizable.strings"
# The app redistributes third-party models: ship the licenses with it.
cp ../LICENSE ../THIRD_PARTY_NOTICES.md "$APP/"

if [ -n "${SIMULATOR:-}" ]; then
    codesign --force --sign - "$APP"
    echo "✓ Built $(pwd)/$APP"
    exit 0
fi

echo "→ Packaging"
xattr -cr "$APP"
(cd build && rm -f MultiBlur.ipa && zip -qry MultiBlur.ipa Payload)
echo "✓ Built $(pwd)/build/MultiBlur.ipa"
