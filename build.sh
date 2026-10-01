#!/bin/bash
# YunGet Web 构建脚本：kotlinc 直接编译 + 打包 fat jar
# 适用于 Gradle/Maven 无法使用的受限网络环境；普通环境也可直接用。
set -e
cd "$(dirname "$0")"

KOTLIN_VERSION=2.0.21
TOOLS_DIR="${TOOLS_DIR:-$HOME/tools}"
KOTLINC="$TOOLS_DIR/kotlinc/bin/kotlinc"

# 1. Kotlin 编译器
if [ ! -x "$KOTLINC" ]; then
  echo "下载 Kotlin 编译器 $KOTLIN_VERSION ..."
  mkdir -p "$TOOLS_DIR"
  curl -sL --max-time 300 -o /tmp/kotlin-compiler.zip \
    "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"
  unzip -q -o /tmp/kotlin-compiler.zip -d "$TOOLS_DIR"
fi

# 2. 依赖（迷你 Maven 解析器，按 pom 传递解析并下载到本地仓库布局）
if [ ! -f /tmp/cp.txt ]; then
  echo "解析并下载依赖 ..."
  python3 scripts/fetch_deps.py \
    "org.jetbrains.kotlin:kotlin-stdlib:${KOTLIN_VERSION}" \
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0" \
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3" \
    "io.ktor:ktor-server-netty-jvm:2.3.12" \
    "io.ktor:ktor-server-content-negotiation-jvm:2.3.12" \
    "io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12" \
    "io.ktor:ktor-server-status-pages-jvm:2.3.12" \
    "io.ktor:ktor-server-call-logging-jvm:2.3.12" \
    "com.squareup.okhttp3:okhttp:4.12.0" \
    "com.squareup.okio:okio-jvm:3.9.0" \
    "org.json:json:20240303" \
    "org.slf4j:slf4j-simple:2.0.13"
fi

# 3. 编译
echo "编译 Kotlin 源码 ..."
export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}"
export PATH="$JAVA_HOME/bin:$PATH"
CP=$(cat /tmp/cp.txt)
rm -rf /tmp/yunget-classes && mkdir -p /tmp/yunget-classes
find backend/src/main/kotlin turbodl-core/src/main/kotlin \
     turbo-plugin-runtime/src/main/kotlin turbo-plugin-bootstrap/src/main/kotlin \
     turbo-plugin-hls/src/main/kotlin -name "*.kt" > /tmp/sources.txt
wc -l /tmp/sources.txt
"$KOTLINC" @/tmp/sources.txt -cp "$CP" -d /tmp/yunget-classes -jvm-target 17 \
  -Xplugin="$TOOLS_DIR/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar" -nowarn

# 4. 打包 fat jar（输出 yunget-web.jar）
echo "打包 fat jar ..."
python3 scripts/package.py
echo "构建完成: $(pwd)/yunget-web.jar"
