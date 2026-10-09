.PHONY: setup build install device-test e2e assets sprites toxicity synonyms plant-types descriptions hazard-vectors labels crop-reference gate-harness gate-pull reference test pipeline-test lint ktlint detekt android-lint pipeline-lint actionlint secret-scan ai-checks clean

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

# -r keeps app data, so gate-harness runs not yet pulled survive reinstalls.
install: build
	adb install -r -d app/build/outputs/apk/debug/app-debug.apk

# On-device instrumented tests. Not connectedAndroidTest: it uninstalls the app afterwards, deleting gate-harness runs not yet pulled.
device-test: install
	adb install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	@adb shell am instrument -w -e notPackage dev.anchildress1.wildfind.e2e $(WILDFIND_PACKAGE).test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -qE '^OK \([1-9][0-9]* tests?\)'

# End-to-end UI tests only (the e2e package) on the phone; they need signal, since each hunt makes one iNat pull.
e2e:
	$(GRADLE) -PisolatedE2e=true :app:assembleDebug :app:assembleDebugAndroidTest
	adb install -r -d app/build/outputs/apk/debug/app-debug.apk
	adb install -r -d -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	@adb shell am instrument -w -e package dev.anchildress1.wildfind.e2e dev.anchildress1.wildfind.e2e.test/androidx.test.runner.AndroidJUnitRunner | tee /dev/stderr | grep -qE '^OK \([1-9][0-9]* tests?\)'

# S05 gate harness: verify on each Capture tap (3 frames), memory, and heat, logged on the phone.
# GATE_TARGET is an eligible West Georgia species, or grass for the tutorial. Back ends a run.
GATE_TARGET ?= Quercus nigra
gate-harness: install
	adb shell am start -n $(WILDFIND_PACKAGE)/dev.anchildress1.wildfind.harness.GateHarnessActivity --es target "'$(GATE_TARGET)'"

# Copies harness runs off the phone and summarizes new ones. One folder for all of them: each run's name starts with
# its date and time, so pulling again adds new runs instead of copying old ones into another day's folder.
GATE_DIR ?= docs/results/gate
gate-pull:
	mkdir -p $(GATE_DIR)
	adb pull /sdcard/Android/data/$(WILDFIND_PACKAGE)/files/gate/. $(GATE_DIR)
	$(UV) run python -W error -m wild_find_pipeline.gate_summary $(GATE_DIR)

# Bundled models and tables into app/generated/assets (gitignored); every app build needs them.
assets:
	$(UV) run --group reference python -W error -m wild_find_pipeline.assets

# Repacks Briar's source sprite sheets into the committed app/src/main/assets/briar/.
sprites:
	$(UV) run python -W error -m wild_find_pipeline.sprites

# S10: rebuilds the committed pipeline/data/toxicity.json from Wikipedia, USDA PLANTS, and GBIF; CI never runs it.
toxicity:
	$(UV) run --group reference python -W error -m wild_find_pipeline.toxicity

# Rebuilds the committed pipeline/data/synonyms.json from the GBIF backbone so drifted iNat names find their row.
synonyms:
	$(UV) run --group reference python -W error -m wild_find_pipeline.synonyms

# S17: rebuilds the committed pipeline/data/plant_types.json from USDA PLANTS growth habit and GBIF taxonomy.
plant-types:
	$(UV) run --group reference python -W error -m wild_find_pipeline.plant_types

# Rebuilds the committed pipeline/data/descriptions.json: a kid-level sentence per row from USDA PLANTS traits.
descriptions:
	$(UV) run --group reference python -W error -m wild_find_pipeline.descriptions

# Rebuilds the committed hazard_vectors.json; pulls the 3.9 GB BioCLIP teacher (as does reference), so CI runs neither.
hazard-vectors:
	$(UV) run --group reference python -W error -m wild_find_pipeline.hazard_vectors

# Rebuilds the committed labels.npy and labels.json from the pinned teacher (3.9 GB), so CI never runs it.
labels:
	$(UV) run --group reference python -W error -m wild_find_pipeline.label_vectors

# Pillow crops the JVM frame tests must match pixel for pixel.
crop-reference:
	$(UV) run --group reference python -W error -m wild_find_pipeline.crop_reference

# Parity references for the on-device tests: BioCLIP (Day 1) and the bundled plant gate.
reference: assets crop-reference
	$(UV) run --group reference python -W error -m wild_find_pipeline.reference
	$(UV) run --group reference python -W error -m wild_find_pipeline.gate_reference

test: pipeline-test
	$(GRADLE) :core:test :core:koverVerify

pipeline-test:
	$(UV) run pytest pipeline/tests

lint: ktlint detekt android-lint pipeline-lint actionlint

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

actionlint:
	actionlint

secret-scan:
	gitleaks git --redact

ai-checks: lint test build secret-scan

clean:
	$(GRADLE) clean
