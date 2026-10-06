.PHONY: setup build install device-test fetch-models push-models reference test pipeline-test lint ktlint detekt android-lint pipeline-lint shellcheck actionlint secret-scan ai-checks clean

SHELL := /bin/bash

# Values already in the shell environment win over .env, as with any dotenv loader.
SHELL_ANDROID_SERIAL := $(ANDROID_SERIAL)
SHELL_WILDFIND_PACKAGE := $(WILDFIND_PACKAGE)
-include .env
ANDROID_SERIAL := $(or $(SHELL_ANDROID_SERIAL),$(ANDROID_SERIAL))
WILDFIND_PACKAGE := $(or $(SHELL_WILDFIND_PACKAGE),$(WILDFIND_PACKAGE),dev.anchildress1.wildfind.debug)
export WILDFIND_PACKAGE
# adb treats an empty ANDROID_SERIAL as a device named ""; export it only when set.
ifneq ($(strip $(ANDROID_SERIAL)),)
export ANDROID_SERIAL
else
# make re-exports inherited variables, so an empty one must be dropped explicitly.
unexport ANDROID_SERIAL
endif

# Pin JAVA_HOME to the .sdkmanrc JDK; empty in CI where setup-java already exports it.
SDKMAN_JAVA := $(HOME)/.sdkman/candidates/java/$(shell sed -n 's/^java=//p' .sdkmanrc)
JAVA_HOME_ENV := $(if $(wildcard $(SDKMAN_JAVA)/bin/java),JAVA_HOME=$(SDKMAN_JAVA),)
JAVA := $(if $(wildcard $(SDKMAN_JAVA)/bin/java),$(SDKMAN_JAVA)/bin/java,java)
GRADLE := $(JAVA_HOME_ENV) ./gradlew --console=plain --warning-mode=fail
# Homebrew's detekt launcher can't take JVM flags, so run its jar directly; CI passes DETEKT_JAR.
DETEKT_JAR ?= $(shell sed -n 's/.*-jar "\([^"]*\)".*/\1/p' "$$(command -v detekt)" 2>/dev/null)
UV := uv --project pipeline

setup:
	lefthook install
	$(UV) sync

# Assembles the instrumented tests too, so a broken on-device test fails here, not on the phone.
build:
	$(GRADLE) :app:assembleDebug :app:assembleDebugAndroidTest

# -r keeps app data, so a sideloaded model survives reinstalls.
install: build
	adb install -r -d app/build/outputs/apk/debug/app-debug.apk

# On-device instrumented tests. Not connectedAndroidTest: it uninstalls the app afterwards, deleting the sideloaded model.
device-test: install
	$(GRADLE) :app:assembleDebugAndroidTest
	adb install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	@adb shell am instrument -w $(WILDFIND_PACKAGE).test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -qE '^OK \([1-9][0-9]* tests?\)'

fetch-models:
	./scripts/models.sh fetch

push-models:
	./scripts/models.sh push

# Day-1 parity reference for the on-device BioCLIP check; needs make fetch-models first.
reference:
	$(UV) run --group reference python -W error -m wild_find_pipeline.reference

test: pipeline-test
	$(GRADLE) :core:test :core:koverVerify

pipeline-test:
	$(UV) run pytest pipeline/tests

lint: ktlint detekt android-lint pipeline-lint shellcheck actionlint

ktlint:
	ktlint --log-level=error

# The flag silences JDK 24+ sun.misc.Unsafe warnings raised inside detekt's bundled Kotlin compiler.
detekt:
	@test -f "$(DETEKT_JAR)" || { echo "❌ detekt jar not found; brew install detekt or set DETEKT_JAR"; exit 1; }
	$(JAVA) --sun-misc-unsafe-memory-access=allow -jar "$(DETEKT_JAR)" --build-upon-default-config --config detekt.yml --input core/src,app/src

android-lint:
	$(GRADLE) :app:lintDebug

pipeline-lint:
	$(UV) run ruff check pipeline
	$(UV) run ruff format --check pipeline

shellcheck:
	shellcheck scripts/*.sh

actionlint:
	actionlint

secret-scan:
	gitleaks git --redact

ai-checks: lint test build secret-scan

clean:
	$(GRADLE) clean
