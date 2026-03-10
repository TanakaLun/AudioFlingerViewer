#!/bin/bash
set -e

generate_release_notes() {
  local VERSION_NAME=$1
  local VERSION_CODE=$2
  
  LATEST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
  
  if [[ -n "$LATEST_TAG" ]]; then
    # Get commits since last tag - use format without --oneline to avoid file lists
    CHANGES=$(git log --pretty=format:"* %s (%h) by %an" ${LATEST_TAG}..HEAD 2>/dev/null)
    COMMITS_COUNT=$(git rev-list --count ${LATEST_TAG}..HEAD 2>/dev/null)
    COMMIT_RANGE="${LATEST_TAG}..HEAD"
  else
    CHANGES=$(git log --pretty=format:"* %s (%h) by %an" HEAD 2>/dev/null)
    COMMITS_COUNT=$(git rev-list --count HEAD 2>/dev/null)
    COMMIT_RANGE="all commits"
  fi
  
  # Get PRs merged
  PRS=$(git log --grep="Merge pull request" --pretty=format:"* %s" 2>/dev/null | sed -E 's/Merge pull request #([0-9]+).*from.*/  * PR #\1/g' || echo "")
  
  # Ensure we have clean output without file listings
  cat << EOF | tee /dev/null
## 🚀 RikoChi v${VERSION_NAME} (Build ${VERSION_CODE})

### 📦 Download
* 📱 Release APK: Signed release version

### ✨ Changes

#### 📋 Commits (${COMMITS_COUNT:-0})
${CHANGES:-* No new commits}

#### 🔀 Pull Requests
${PRS:-* No PRs merged}

### ⚙️ Build Info
* Version Code: ${VERSION_CODE}
* Version Name: ${VERSION_NAME}
* Build Date: $(date +'%Y-%m-%d %H:%M:%S')
* Commit Range: ${COMMIT_RANGE}
* Build ID: ${GITHUB_RUN_ID:-local}

---
*Built by GitHub Actions*
EOF
}

modify_gradle_file() {
  local GRADLE_FILE=$1
  local VERSION_CODE=$2
  local VERSION_NAME=$3
  
  if [[ ! -f "$GRADLE_FILE" ]]; then
    echo "Error: Gradle file not found: $GRADLE_FILE"
    exit 1
  fi
  
  cp "$GRADLE_FILE" "$GRADLE_FILE.bak"
  
  # Replace versionCode line - more precise matching
  sed -i -E "s/^( *versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${VERSION_CODE}/" "$GRADLE_FILE"
  
  # Replace versionName line - more precise matching
  sed -i -E "s/^( *versionName[[:space:]]*=[[:space:]]*).*/\1\"${VERSION_NAME}\"/" "$GRADLE_FILE"
  
  echo "Modified versionCode to: ${VERSION_CODE}"
  echo "Modified versionName to: ${VERSION_NAME}"
  
  # Show the modified lines
  grep -n "versionCode\|versionName" "$GRADLE_FILE" || true
}

prepare_assets() {
  local VERSION_NAME=$1
  local VERSION_CODE=$2
  local WORKSPACE=$3
  
  mkdir -p release-assets
  
  # Find and copy release APK
  RELEASE_APK=$(find ${WORKSPACE}/app/build/outputs/apk/release -name "*.apk" ! -name "*unsigned*.apk" ! -name "*unaligned*.apk" 2>/dev/null | head -1)
  if [[ -n "$RELEASE_APK" ]]; then
    RELEASE_FILENAME="RikoChi-v${VERSION_NAME}-release.apk"
    cp "$RELEASE_APK" "release-assets/${RELEASE_FILENAME}"
    echo "✅ Release APK: ${RELEASE_FILENAME}"
    
    # Generate checksum
    (cd release-assets && sha256sum "${RELEASE_FILENAME}" > "${RELEASE_FILENAME}.sha256")
  else
    echo "⚠️ Release APK not found"
  fi
  
  # Find and copy mapping.txt
  MAPPING_FILE=$(find ${WORKSPACE}/app/build/outputs/mapping/release -name "mapping.txt" 2>/dev/null | head -1)
  if [[ -n "$MAPPING_FILE" ]]; then
    MAPPING_FILENAME="mapping-v${VERSION_NAME}-${VERSION_CODE}.txt"
    cp "$MAPPING_FILE" "release-assets/${MAPPING_FILENAME}"
    echo "✅ Mapping file: ${MAPPING_FILENAME}"
  fi
  
  # Output tag name for next steps
  echo "version_tag=v${VERSION_NAME}-build${VERSION_CODE}" >> $GITHUB_OUTPUT
}

case "$1" in
  generate-notes)
    generate_release_notes "$2" "$3"
    ;;
  modify-gradle)
    modify_gradle_file "$2" "$3" "$4"
    ;;
  prepare-assets)
    prepare_assets "$2" "$3" "$4"
    ;;
  *)
    echo "Usage: $0 {generate-notes|modify-gradle|prepare-assets} [args...]"
    exit 1
    ;;
esac