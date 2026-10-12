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

test_image NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG 0790b191c33408a4ae268d678b6077c6a1c079c7
test_image NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG 945960bfaf532277b0e48013421e9323da2047d2
test_image N_LRGB_1622XRZS_0790198_CYL_L_AUTOGENJ01.IMG               5d738126f1d796d4aa0d8eb1269a72a72855838f
test_image ZLF_1618_0810574341_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 3f380efa9dc6ab116c9e2a593652bec4d736cbb3
test_image ZLF_1618_0810574647_473FDR_N0790198ZCAM09697_0340LMJ01.IMG d43612e6c512fa742b2330d0e600c3eb4e2c38d9
test_image ZLF_1618_0810575350_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 5f34138636aa0254a76518bd62fef09033e7689a
test_image ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 6b07603ef210678713dc78be90358571082663a5
test_image Z_LRGB_1618XRZS_0790198_ORR_L_45M01CMJ08.IMG               661a2f948fc17737be4954fc9dde13170e427a36

test_image NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG 95416015d4757930716e287e8d81efc428a20fc0 "&overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D" 

test_image ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 5dfc42f6030e383b951a832f39e16c3d2e49f961 "&extremaStretch=true&stretchLow=1000&stretchHigh=3000"

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

#SSRF filter: image URLs on non-public hosts are rejected with 400
#URLs are percent encoded, also [] would otherwise be interpreted by curl as globs
test_fail "?image=http%3A%2F%2Flocalhost%3A8080%2Fx.IMG" 400                       #http://localhost:8080/x.IMG
test_fail "?image=http%3A%2F%2FLOCALHOST.%3A8080%2Fx.IMG" 400                      #http://LOCALHOST.:8080/x.IMG
test_fail "?image=http%3A%2F%2Ffoo.localhost%2Fx.IMG" 400                          #http://foo.localhost/x.IMG
test_fail "?image=http%3A%2F%2F127.0.0.1%3A8080%2Fx.IMG" 400                       #http://127.0.0.1:8080/x.IMG
test_fail "?image=http%3A%2F%2F127.1%2Fx.IMG" 400                                  #http://127.1/x.IMG
test_fail "?image=http%3A%2F%2F2130706433%2Fx.IMG" 400                             #http://2130706433/x.IMG (127.0.0.1)
test_fail "?image=http%3A%2F%2F0.0.0.0%2Fx.IMG" 400                                #http://0.0.0.0/x.IMG
test_fail "?image=http%3A%2F%2F10.1.2.3%2Fx.IMG" 400                               #http://10.1.2.3/x.IMG
test_fail "?image=http%3A%2F%2F172.16.0.1%2Fx.IMG" 400                             #http://172.16.0.1/x.IMG
test_fail "?image=http%3A%2F%2F192.168.1.1%2Fx.IMG" 400                            #http://192.168.1.1/x.IMG
test_fail "?image=http%3A%2F%2F100.100.100.200%2Fx.IMG" 400                        #http://100.100.100.200/x.IMG (CGNAT)
test_fail "?image=http%3A%2F%2F169.254.169.254%2Flatest%2Fmeta-data%2Fx.IMG" 400   #http://169.254.169.254/latest/meta-data/x.IMG
test_fail "?image=http%3A%2F%2Fmetadata.google.internal%2Fx.IMG" 400               #http://metadata.google.internal/x.IMG
test_fail "?image=http%3A%2F%2Fexample.com%40127.0.0.1%2Fx.IMG" 400                #http://example.com@127.0.0.1/x.IMG
test_fail "?image=http%3A%2F%2F%5B%3A%3A1%5D%3A8080%2Fx.IMG" 400                   #http://[::1]:8080/x.IMG
test_fail "?image=http%3A%2F%2F%5Bfe80%3A%3A1%5D%2Fx.IMG" 400                      #http://[fe80::1]/x.IMG
test_fail "?image=http%3A%2F%2F%5Bfd00%3Aec2%3A%3A254%5D%2Fx.IMG" 400              #http://[fd00:ec2::254]/x.IMG (AWS IPv6 metadata)
test_fail "?image=http%3A%2F%2F%5B%3A%3Affff%3A169.254.169.254%5D%2Fx.IMG" 400     #http://[::ffff:169.254.169.254]/x.IMG
test_fail "?image=http%3A%2F%2F%5B64%3Aff9b%3A%3Aa9fe%3Aa9fe%5D%2Fx.IMG" 400       #http://[64:ff9b::a9fe:a9fe]/x.IMG (NAT64)
test_fail "?image=http%3A%2F%2F%5B2002%3Aa9fe%3Aa9fe%3A%3A%5D%2Fx.IMG" 400         #http://[2002:a9fe:a9fe::]/x.IMG (6to4)
test_fail "?image=http%3A%2F%2Fno-such-host.invalid%2Fx.IMG" 400                   #http://no-such-host.invalid/x.IMG

echo "SUCCESS"
