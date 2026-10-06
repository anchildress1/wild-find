.PHONY: setup build install fetch-models push-models reference test pipeline-test lint ktlint detekt android-lint pipeline-lint secret-scan ai-checks clean

SHELL := /bin/bash

# Pin JAVA_HOME to the .sdkmanrc JDK; empty in CI where setup-java already exports it.
SDKMAN_JAVA := $(HOME)/.sdkman/candidates/java/$(shell sed -n 's/^java=//p' .sdkmanrc)
JAVA_HOME_ENV := $(if $(wildcard $(SDKMAN_JAVA)/bin/java),JAVA_HOME=$(SDKMAN_JAVA),)
GRADLE := $(JAVA_HOME_ENV) ./gradlew --console=plain --warning-mode=fail
UV := uv --project pipeline

setup:
	lefthook install
	$(UV) sync

build:
	$(GRADLE) :app:assembleDebug

# -r keeps app data, so a sideloaded model survives reinstalls.
install: build
	adb install -r -d app/build/outputs/apk/debug/app-debug.apk

fetch-models:
	./scripts/models.sh fetch

push-models:
	./scripts/models.sh push

# Day-1 parity reference for the on-device BioCLIP check; needs make fetch-models first.
reference:
	$(UV) run python -W error -m wild_find_pipeline.reference

test: pipeline-test
	$(GRADLE) :core:test :core:koverVerify

pipeline-test:
	$(UV) run pytest pipeline/tests

lint: ktlint detekt android-lint pipeline-lint

ktlint:
	ktlint --log-level=error

detekt:
	$(JAVA_HOME_ENV) detekt --build-upon-default-config --config detekt.yml --input core/src,app/src

android-lint:
	$(GRADLE) :app:lintDebug

pipeline-lint:
	$(UV) run ruff check pipeline
	$(UV) run ruff format --check pipeline

secret-scan:
	gitleaks git --redact

ai-checks: lint test build

clean:
	$(GRADLE) clean
