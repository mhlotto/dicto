SHELL := /bin/bash

GRADLEW := gradle
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk
VOSK_MODEL_NAME := vosk-model-small-en-us-0.15
VOSK_MODEL_URL := https://alphacephei.com/vosk/models/$(VOSK_MODEL_NAME).zip
VOSK_ASSET_ROOT := app/src/main/assets/models
VOSK_MODEL_DIR := $(VOSK_ASSET_ROOT)/$(VOSK_MODEL_NAME)
VOSK_TMP_DIR := /tmp
VOSK_ZIP := $(VOSK_TMP_DIR)/$(VOSK_MODEL_NAME).zip
VOSK_UNPACKED := $(VOSK_TMP_DIR)/$(VOSK_MODEL_NAME)

.PHONY: help build prepare-vosk-model check-vosk-model install clean precommit

help:
	@echo "Dicto make targets"
	@echo ""
	@echo "  build                    Download/copy Vosk model asset, then build one APK with all engines"
	@echo "                           Set DICTO_CACHE=1 to reuse $(VOSK_UNPACKED) when present"
	@echo ""
	@echo "  install                  Build the all-engines APK, then install it"
	@echo ""
	@echo "  clean                    Remove Gradle/build artifacts"
	@echo "  precommit                Run lint/security/dependency checks"

prepare-vosk-model:
	mkdir -p "$(VOSK_ASSET_ROOT)"
	@if [[ "$(DICTO_CACHE)" == "1" && -d "$(VOSK_UNPACKED)" ]]; then \
		echo "Using cached Vosk model directory: $(VOSK_UNPACKED)"; \
	else \
		cd "$(VOSK_TMP_DIR)" && curl -LO "$(VOSK_MODEL_URL)"; \
		cd "$(VOSK_TMP_DIR)" && unzip -q -o "$(VOSK_ZIP)"; \
	fi
	rm -rf "$(VOSK_MODEL_DIR)"
	cp -R "$(VOSK_UNPACKED)" "$(VOSK_ASSET_ROOT)/"

check-vosk-model:
	@test -f "$(VOSK_MODEL_DIR)/am/final.mdl" || (echo "Missing $(VOSK_MODEL_DIR)/am/final.mdl"; exit 1)
	@test -f "$(VOSK_MODEL_DIR)/conf/model.conf" || (echo "Missing $(VOSK_MODEL_DIR)/conf/model.conf"; exit 1)

build: prepare-vosk-model check-vosk-model
	$(GRADLEW) assembleDebug

install: build
	adb install -r $(DEBUG_APK)

clean:
	$(GRADLEW) clean
	rm -rf build out
	rm -f dist/*.zip

precommit:
	$(GRADLEW) lint
	semgrep --config auto --exclude-rule java.android.security.exported_activity.exported_activity .
	osv-scanner scan source -L app/gradle.lockfile -L buildscript-gradle.lockfile
