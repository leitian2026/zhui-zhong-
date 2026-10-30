#!/bin/sh
# 配置检查器测试：字段表同步 → 编译纯 Java 部分 → 跑回归测试和文档示例。需要 JDK 17+，不需要 Android SDK。
set -e
cd "$(dirname "$0")/.."
if [ "$1" = "--fix" ]; then python3 tools/gen_schema.py; else python3 tools/gen_schema.py --check; fi
# 关系表和代码对得上吗（同一个判断里一起出现的字段是否声明了前提；声明的前提是否有核对证据）
python3 tools/audit_relations.py
# 文档有没有跟上代码（版本说明、粘贴命令的 key）
python3 tools/check_docs_sync.py
OUT=$(mktemp -d)
SRC=app/src/main/java/com/textrec/lsp
STUB=$OUT/stub
mkdir -p "$STUB/com/textrec/lsp"
# RecConfig 只用到 Rec 里的几个类型名常量，这里用不依赖 Android 的替身
cat > "$STUB/com/textrec/lsp/Rec.java" <<'JAVA'
package com.textrec.lsp;
final class Rec {
    static final String TEXTVIEW = "TextView", CANVAS = "Canvas", COMPOSE = "Compose", TEXTCLEAR = "Clear", TEXTSET = "SetOnly", STATE = "STATE", ICON = "ICON", TRACE = "TRACE";
}
JAVA
javac -encoding UTF-8 -d "$OUT/classes" \
  $SRC/ConfigCheck.java $SRC/CheckText.java $SRC/CheckFields.java $SRC/CheckTrace.java $SRC/CheckTasks.java $SRC/CheckCross.java $SRC/ConfigSchema.java $SRC/Presets.java $SRC/PasteBlocks.java $SRC/RecConfig.java $SRC/TargetStatus.java $SRC/KnownNames.java $SRC/FormModel.java $SRC/Relations.java $SRC/TaskStats.java \
  "$STUB/com/textrec/lsp/Rec.java" tools/ConfigCheckTest.java
# --fix：把文档里的预设表按 Presets.java 重写（字段表用 python3 tools/gen_schema.py 重写）
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/classes" com.textrec.lsp.ConfigCheckTest "$@" app/src/main/assets/ai_manual.md app/src/main/assets/ai_guide.md
