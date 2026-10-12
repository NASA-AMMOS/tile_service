#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir

ver=9.0.123

curl -LO https://archive.apache.org/dist/tomcat/tomcat-9/v${ver}/bin/apache-tomcat-${ver}.zip

unzip -o apache-tomcat-${ver}.zip

mv apache-tomcat-${ver} tomcat

chmod a+x tomcat/bin/*.sh
