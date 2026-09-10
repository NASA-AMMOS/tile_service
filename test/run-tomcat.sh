#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

if [ ! -d test/tomcat ]; then
  echo "test/tomcat/ not found, run test/download-tomcat.sh"
  exit 1
fi

./test/tomcat/bin/catalina.sh run
