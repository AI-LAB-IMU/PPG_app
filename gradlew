#!/usr/bin/env sh
DIR="$( cd "$( dirname "$0" )" && pwd )"
exec "$DIR/gradle/wrapper/gradle-8.7/bin/gradle" "$@"