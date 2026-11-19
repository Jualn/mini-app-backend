#!/bin/sh
# Use the exact release JAR. Spring Boot loads its nested runtime dependencies.
set -eu

if [ "$#" -lt 3 ]; then
    printf '%s\n' 'Usage: invoke-root-operations.sh <app.jar> <jdbc-url> inventory | inspect ... | <plan.json> [--execute <sha256>]' >&2
    exit 2
fi

jar=$1
shift
if [ ! -r "$jar" ]; then
    printf '%s\n' 'Release app.jar must be readable' >&2
    exit 2
fi
case "$jar" in
    /*) ;;
    *) jar="$(pwd)/$jar" ;;
esac

java=java
if [ -n "${JAVA_HOME:-}" ]; then
    java="$JAVA_HOME/bin/java"
fi

exec "$java" -Dloader.main=cn.jualn.miniapp.module.authorization.service.RbacRootOperations \
    -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
