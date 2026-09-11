#!/bin/sh
# Android Studio can use its configured Gradle distribution. This lightweight launcher
# intentionally avoids bundling a binary Gradle wrapper jar in the source ZIP.
exec gradle "$@"
