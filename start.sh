#!/usr/bin/env bash
set -e

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if ! command -v python3 >/dev/null 2>&1; then
    printf '%s\n' "Python 3 was not found. Install Python 3 and try again."
    exit 1
fi

exec python3 ms5_viewer.py
