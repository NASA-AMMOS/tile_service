#!/usr/bin/env bash

if [[ $# -lt 1 ]]; then
  echo "USAGE run-cli.sh PATH"
  exit 1
fi

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

java -XX:-TieredCompilation -Xmx10g -jar $script_dir/image_tiler.jar -dcd $script_dir/tmp -it 0 -zt 0 -d -i $1 -m M20 -fc -cl $script_dir/cache

