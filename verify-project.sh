#!/bin/bash
# YuMark 项目验证脚本：克隆后/onboarding 快速排错用的纯存在性检查。
# 不含编译与测试逻辑——那条门禁在 CI（.github/workflows/android.yml）与
# ./gradlew :app:testDebugUnitTest 里，别在这里重复。
# 历史教训：检查路径必须与 download-js-libs.sh 的 ASSETS_DIR 保持同源
# （app/src/main/assets/raw），否则就是永远误报的僵尸脚本。

echo "🔍 YuMark Project Verification"
echo "================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

check_file() {
    if [ -f "$1" ]; then
        echo -e "${GREEN}✓${NC} $1"
        return 0
    else
        echo -e "${RED}✗${NC} $1 ${RED}(MISSING)${NC}"
        return 1
    fi
}

check_dir() {
    if [ -d "$1" ]; then
        echo -e "${GREEN}✓${NC} $1/"
        return 0
    else
        echo -e "${RED}✗${NC} $1/ ${RED}(MISSING)${NC}"
        return 1
    fi
}

missing_count=0

echo "📦 Configuration Files"
check_file "settings.gradle.kts" || ((missing_count++))
check_file "build.gradle.kts" || ((missing_count++))
check_file "gradle.properties" || ((missing_count++))
check_file "gradle/libs.versions.toml" || ((missing_count++))
check_file "app/build.gradle.kts" || ((missing_count++))
check_file "app/proguard-rules.pro" || ((missing_count++))
echo ""

echo "📱 Android Files"
check_file "app/src/main/AndroidManifest.xml" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/YuMarkApplication.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/MainActivity.kt" || ((missing_count++))
check_file "app/src/main/res/xml/backup_rules.xml" || ((missing_count++))
check_file "app/src/main/res/xml/data_extraction_rules.xml" || ((missing_count++))
check_file "app/src/main/res/xml/file_paths.xml" || ((missing_count++))
echo ""

echo "🎨 UI Components"
check_file "app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/presentation/filelist/FileListScreen.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/presentation/settings/SettingsScreen.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/presentation/trash/TrashScreen.kt" || ((missing_count++))
echo ""

echo "💾 Data Layer"
check_file "app/src/main/java/com/yumark/app/data/local/db/AppDatabase.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/data/repository/DocumentRepositoryImpl.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/data/repository/SyncRepositoryImpl.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/data/sync/SyncPlanner.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/data/sync/SyncWorker.kt" || ((missing_count++))
echo ""

echo "🌐 WebView Renderer"
check_file "app/src/main/assets/templates/renderer.html" || ((missing_count++))
echo ""

echo "📚 JavaScript Libraries（与 download-js-libs.sh 同源：assets/raw）"
check_file "app/src/main/assets/raw/markedjs.js" || ((missing_count++))
check_file "app/src/main/assets/raw/katexjs.js" || ((missing_count++))
check_file "app/src/main/assets/raw/mermaidjs.js" || ((missing_count++))
check_file "app/src/main/assets/raw/prism.js" || ((missing_count++))
check_file "app/src/main/assets/raw/purify.js" || ((missing_count++))
check_file "app/src/main/assets/raw/ym-sanitize.js" || ((missing_count++))
echo ""

echo "🗄 Room Schemas（迁移测试的前提，必须入库）"
check_dir "app/schemas/com.yumark.app.data.local.db.AppDatabase" || ((missing_count++))
echo ""

echo "🧪 Tests"
check_dir "app/src/test/java/com/yumark/app" || ((missing_count++))
check_dir "app/src/androidTest/java/com/yumark/app" || ((missing_count++))
echo ""

echo "🔁 CI / Release"
check_file ".github/workflows/android.yml" || ((missing_count++))
check_file ".github/workflows/release.yml" || ((missing_count++))
echo ""

echo "📖 Documentation"
check_file "README.md" || ((missing_count++))
check_file "CHANGELOG.md" || ((missing_count++))
check_file "CONTRIBUTING.md" || ((missing_count++))
check_file "PRIVACY.md" || ((missing_count++))
check_file "HOW_TO_RELEASE.md" || ((missing_count++))
echo ""

echo "================================"
if [ $missing_count -eq 0 ]; then
    echo -e "${GREEN}✅ All checks passed!${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Run: bash download-js-libs.sh (首次克隆后补齐/校验 JS 库)"
    echo "2. Open in Android Studio, or: ./gradlew :app:assembleDebug"
    echo "3. Run unit tests: ./gradlew :app:testDebugUnitTest"
    exit 0
else
    echo -e "${RED}❌ $missing_count file(s) missing${NC}"
    exit 1
fi
