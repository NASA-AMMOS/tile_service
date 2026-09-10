#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir

curl -LO https://dlcdn.apache.org/tomcat/tomcat-9/v9.0.120/bin/apache-tomcat-9.0.120.zip

unzip -o apache-tomcat-9.0.120.zip

mv apache-tomcat-9.0.120 tomcat

chmod a+x tomcat/bin/*.sh
