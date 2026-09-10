#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

if [ ! -d test/tomcat/webapps ]; then
  echo "test/tomcat/webapps/ not found, run test/download-tomcat.sh"
  exit 1
fi

mvn clean

mvn -P war-localhost-fscache package

cp target/image_tiler.war test/tomcat/webapps
