#!/usr/bin/env bash

if [[ $# -lt 1 ]] || [[ ! -d $1 ]]; then
  echo "USAGE: sha1.sh DIR"
  exit 1
fi

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
md_json="$1/metadata.json"
md_persistent="$1/metadata_persistent.json"

if [[ -f "$md_json" ]]; then "$script_dir/filter-metadata.py" "$md_json" "$md_persistent"; fi

find "$1" -type f ! -name 'metadata.json' -print0 | sort -z | xargs -0 shasum -a 1 | shasum -a 1 | cut -f1 -d' '

if [[ -f "$md_persistent" ]]; then rm -f "$md_persistent"; fi
