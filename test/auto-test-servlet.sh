#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

cleanup() {
  echo "stopping tomcat..."
  ./test/tomcat/bin/catalina.sh stop >/dev/null 2>&1
}

trap cleanup EXIT

test_alive() {
  echo "testing servlet is alive..."

  local status=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/image_tiler/version")
  if [[ "$status" != "200" ]]; then
    echo "FAILURE: HTTP status code $status != 200 for image_tiler/version"
    exit 1
  fi
}

test_image() {
  local f="$1"
  local expected="$2"
  local extra="$3"

  echo "processing $f..."

  local status=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/image_tiler?image=$f$extra")
  if [[ "$status" != "200" ]]; then
    echo "FAILURE: HTTP status code $status != 200 for $f"
    exit 1
  fi

  local completed=false
  for i in {1..100}; do
    sleep 1
    local progress=$(curl -s "http://localhost:8080/image_tiler?image=$f$extra&progress=true")
    if [[ "$progress" == 100* ]]; then
      completed=true
      break
    fi
  done

  if [[ "$completed" != "true" ]]; then
    echo "FAILURE: Processing did not complete within 100s for $f"
    exit 1
  fi

  local hash=`./test/get-hash.sh $f`
  local sha1=`./test/sha1.sh test/cache/$hash`

  if [[ -z "$expected" ]]; then echo SHA1=$sha1;
  elif [[ "$sha1" != "$expected" ]]; then
    echo "FAILURE: SHA1 mismatch $sha1 != $expected for tiling of $f at test/cache/$hash"
    exit 1
  fi
}

test_json() {
  local q="$1"
  local expected="$2"
  echo "testing query $q"

  local response=$(curl -s -w "\n%{http_code}" "http://localhost:8080/image_tiler/$q")
  local status=$(echo "$response" | tail -n1)
  local json_response=$(echo "$response" | sed '$d')

  if [[ "$status" != "200" ]]; then
    echo "FAILURE: HTTP status code $status != 200 for $q"
    exit 1
  fi

  local sha1=$(echo -n "$json_response" | shasum -a 1 | cut -f1 -d' ')

  if [[ -z "$expected" ]]; then echo SHA1=$sha1;
  elif [[ "$sha1" != "$expected" ]]; then
    echo "FAILURE: SHA1 mismatch $sha1 != $expected for $q"
    exit 1
  fi
}

test_fail() {
  local q="$1"
  local expected="$2"
  echo "testing query $q (expect $expected)"
  local status=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/image_tiler/$q")
  if [[ -z "$expected" ]]; then echo STATUS=$status
  elif [[ "$status" != "$expected" ]]; then
    echo "FAILURE: HTTP status code $status != $expected for $q"
    exit 1
  fi
}

echo "clearing cache..."
./test/clear-cache.sh

echo "starting tomcat..."
./test/tomcat/bin/catalina.sh run >/dev/null 2>&1 &

sleep 10

test_alive

test_image NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG 79536112803cbb909fd9df97e86a088587fc0b60
test_image NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG e0c7883df604173c1f4477a29db337f7870db554
test_image N_LRGB_1622XRZS_0790198_CYL_L_AUTOGENJ01.IMG               446673bf0a9febe1dec670006b0f01b69cb3251d
test_image ZLF_1618_0810574341_473FDR_N0790198ZCAM09697_0340LMJ01.IMG bec8f71d9c5158e75e5fece94f815db3d88414b1
test_image ZLF_1618_0810574647_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 536b54ac3912de040c508384fa3d47a92707bac1
test_image ZLF_1618_0810575350_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 7c284a02dad5a064f2cd683d9407527e04a48bf4
test_image ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 6f26800253cbce8acb2ebe217b1ae085423d2c9e
test_image Z_LRGB_1618XRZS_0790198_ORR_L_45M01CMJ08.IMG               957d20261dec6db050b0a716d11db3f69a8dec82

test_image NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG ef9d202da9e9ff01efc8de72b17ed5f66e7ea3ca "&overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D" 

test_image ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG a859511e2e52de2fb02cc29c172eee7b7579b514 "&extremaStretch=true&stretchLow=1000&stretchHigh=3000"

test_json "finder/solrange" a7b2f2b865a55adc05a02fedf40049389f76f7c7
test_json "finder/edrs?sol=50" bd44ee0afbd93ee64dc6ad294e52e2f6a3c29a75
test_json "finder/edrs?sol=00051" 528e05d4fb1281d09622d8692b27b975f265fdf3
test_json "finder/edrs?sol=40" 39c689ba1f17a1686e7d1cb880a69a9665579e3f
test_json "finder/edr?image=00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.IMG" b40e82fc41bd0fa2b9e77589d87abdf219e5864c
test_fail "finder/edr?image=00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.NOT" 404
test_json "finder/rdrs?edr=00051/ids/fdr/ncam/NLF_0051_0671466170_380FDR_N0031950NCAM00408_0A00LLJ01.IMG" 3fb2d9f8b7cd59f75da4e0cf83fcafc14d725e3c
test_fail "finder/rdrs?edr=00051/ids/fdr/ncam/NLF_0051_0671466170_380FDR_N0031950NCAM00408_0A00LLJ01.NOT" 404
test_json "finder/rdr?image=00051/ids/rdr/ncam/NLF_0051_0671466170_380XYM_N0031950NCAM00408_0A00LLJ01.IMG" 250e89adae2be4bcc4d0655057ca0144fda26296
test_fail "finder/rdr?image=00051/ids/rdr/ncam/NLF_0051_0671466170_380XYM_N0031950NCAM00408_0A00LLJ01.NOT" 404
test_json "finder/label?image=00052/ids/rdr/ncam/NLF_0052_0671564560_659RAS_N0032046NCAM03052_0A0195J02.IMG" f026735cff80df64565ae4f6be1f40b0771702d7
test_fail "finder/label?image=00052/ids/rdr/ncam/NLF_0052_0671564560_659RAS_N0032046NCAM03052_0A0195J02.NOT" 404

echo "SUCCESS"
