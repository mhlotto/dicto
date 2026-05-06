SHELL := /bin/bash

GRADLEW := gradle
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk
WHISPER_NATIVE := -Pdicto.enableWhisperNative=true
WHISPER_TINY := -Pdicto.whisperModelAsset=ggml-tiny.en.bin
WHISPER_BASE_Q5 := -Pdicto.whisperModelAsset=ggml-base.en-q5_1.bin
VOSK_MODEL_NAME := vosk-model-small-en-us-0.15
VOSK_MODEL_URL := https://alphacephei.com/vosk/models/$(VOSK_MODEL_NAME).zip
VOSK_ASSET_ROOT := app/src/main/assets/models
VOSK_MODEL_DIR := $(VOSK_ASSET_ROOT)/$(VOSK_MODEL_NAME)
VOSK_TMP_DIR := /tmp
VOSK_ZIP := $(VOSK_TMP_DIR)/$(VOSK_MODEL_NAME).zip
VOSK_UNPACKED := $(VOSK_TMP_DIR)/$(VOSK_MODEL_NAME)

.PHONY: help build build-sr build-whisper-tiny build-whisper-base-q5 build-vosk prepare-vosk-model install install-sr install-whisper-tiny install-whisper-base-q5 install-vosk check-vosk-model clean precommit

help:
	@echo "Dicto make targets"
	@echo ""
	@echo "  build                    Build SpeechRecognizer/default debug APK"
	@echo "  build-sr                 Build SpeechRecognizer/default debug APK"
	@echo "  build-whisper-tiny       Build Whisper debug APK with ggml-tiny.en.bin"
	@echo "  build-whisper-base-q5    Build Whisper debug APK with ggml-base.en-q5_1.bin"
	@echo "  build-vosk               Download/copy Vosk model asset, then build Vosk debug APK"
	@echo "                           Set DICTO_CACHE=1 to reuse $(VOSK_UNPACKED) when present"
	@echo ""
	@echo "  install                  Install the most recently built debug APK"
	@echo "  install-sr               Build SpeechRecognizer/default APK, then install"
	@echo "  install-whisper-tiny     Build Whisper tiny APK, then install"
	@echo "  install-whisper-base-q5  Build Whisper base q5 APK, then install"
	@echo "  install-vosk             Build Vosk APK, then install"
	@echo ""
	@echo "  clean                    Remove Gradle/build artifacts"
	@echo "  precommit                Run lint/security/dependency checks"

build: build-sr

build-sr:
	$(GRADLEW) assembleDebug

build-whisper-tiny:
	$(GRADLEW) assembleDebug $(WHISPER_NATIVE) $(WHISPER_TINY)

build-whisper-base-q5:
	$(GRADLEW) assembleDebug $(WHISPER_NATIVE) $(WHISPER_BASE_Q5)

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

build-vosk: prepare-vosk-model check-vosk-model
	$(GRADLEW) assembleDebug

install:
	adb install -r $(DEBUG_APK)

install-sr: build-sr install

install-whisper-tiny: build-whisper-tiny install

install-whisper-base-q5: build-whisper-base-q5 install

install-vosk: build-vosk install

clean:
	$(GRADLEW) clean
	rm -rf build out
	rm -f dist/*.zip

precommit:
	$(GRADLEW) lint
	semgrep --config auto --exclude-rule java.android.security.exported_activity.exported_activity .
	osv-scanner scan source -L app/gradle.lockfile -L buildscript-gradle.lockfile
