#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

mvn clean

mvn -P jar package

cp target/image_tiler.jar test

cp webapp/resources/openseadragon.min.js test
