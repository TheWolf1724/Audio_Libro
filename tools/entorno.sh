# Uso (desde la raiz del proyecto): source tools/entorno.sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f /usr/bin/java)")")"
export GRADLE_HOME="$HOME/Android/gradle/gradle-9.8.2"
export GRADLE_OPTS="-Dorg.gradle.workers.max=4"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$GRADLE_HOME/bin:$PATH"
