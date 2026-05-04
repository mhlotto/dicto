SHELL := /bin/bash

GRADLEW := gradle
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk

.PHONY: build install clean precommit

build:
	$(GRADLEW) assembleDebug

install: 
	adb install -r $(DEBUG_APK)

clean:
	$(GRADLEW) clean
	rm -rf build out
	rm -f dist/*.zip

precommit:
	$(GRADLEW) lint
	semgrep --config auto --exclude-rule java.android.security.exported_activity.exported_activity .
	osv-scanner scan source -L app/gradle.lockfile -L buildscript-gradle.lockfile
