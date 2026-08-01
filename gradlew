#!/bin/sh

# Gradle wrapper script

# Resolve app home
APP_HOME=$( cd -P "${0%"${0##*/}"}." > /dev/null && printf '%s\n' "$PWD" ) || exit

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Determine java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" \
    $JAVA_OPTS \
    $GRADLE_OPTS \
    -Xmx64m \
    -Xms64m \
    -Dorg.gradle.appname="${0##*/}" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"