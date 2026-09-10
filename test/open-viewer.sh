#!/usr/bin/env bash

if [[ $# -lt 1 ]]; then
  echo "USAGE open-viewer.sh IMAGE"
  exit 1
fi

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

python -m http.server 8088 &

sleep 1

python -m webbrowser "http://localhost:8088/test/viewer.html?"`test/get-hash.sh $1 cache`

sleep 10

kill %%
