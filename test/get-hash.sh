#!/usr/bin/env bash

if [[ $# -lt 1 ]]; then
  echo "USAGE get-hash.sh IMAGE [CACHE}"
  exit 1
fi

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

cl=""
if [[ $# -gt 1 ]]; then
  cl="-cl $2"
fi

java -jar test/image_tiler.jar -i test/$1 -m M20 -fc -pc -q $cl 2>/dev/null
