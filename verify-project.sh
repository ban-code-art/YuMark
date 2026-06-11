#!/bin/bash
# YuMark 项目验证脚本

echo "🔍 YuMark Project Verification"
echo "================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check functions
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
check_file "app/build.gradle.kts" || ((missing_count++))
check_file "app/proguard-rules.pro" || ((missing_count++))
echo ""

echo "📱 Android Files"
check_file "app/src/main/AndroidManifest.xml" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/YuMarkApplication.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/MainActivity.kt" || ((missing_count++))
echo ""

echo "🎨 UI Components"
check_file "app/src/main/java/com/yumark/app/presentation/editor/EditorScreen.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/presentation/filelist/FileListScreen.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/presentation/settings/SettingsScreen.kt" || ((missing_count++))
echo ""

echo "💾 Data Layer"
check_file "app/src/main/java/com/yumark/app/data/local/db/AppDatabase.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/data/repository/DocumentRepositoryImpl.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/data/repository/ImageRepositoryImpl.kt" || ((missing_count++))
echo ""

echo "🌐 WebView Renderer"
check_file "app/src/main/assets/templates/renderer.html" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/core/webview/MarkdownRenderer.kt" || ((missing_count++))
check_file "app/src/main/java/com/yumark/app/core/webview/JsBridge.kt" || ((missing_count++))
echo ""

echo "📚 JavaScript Libraries"
check_file "app/src/main/res/raw/marked.min.js" || ((missing_count++))
check_file "app/src/main/res/raw/katex.min.js" || ((missing_count++))
check_file "app/src/main/res/raw/mermaid.min.js" || ((missing_count++))
check_file "app/src/main/res/raw/prism.js" || ((missing_count++))
echo ""

echo "🧪 Tests"
check_dir "app/src/test/java/com/yumark/app" || ((missing_count++))
echo ""

echo "📖 Documentation"
check_file "README.md" || ((missing_count++))
check_file "CONTRIBUTING.md" || ((missing_count++))
check_file "docs/ARCHITECTURE.md" || ((missing_count++))
echo ""

echo "================================"
if [ $missing_count -eq 0 ]; then
    echo -e "${GREEN}✅ All checks passed!${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Run: bash download-js-libs.sh"
    echo "2. Open in Android Studio"
    echo "3. Build and run on emulator/device"
    exit 0
else
    echo -e "${RED}❌ $missing_count file(s) missing${NC}"
    exit 1
fi
