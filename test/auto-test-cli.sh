#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir/..

test_image() {
  local f="$1"
  local expected="$2"

  echo "processing $f..."
  ./test/run-cli.sh test/$f >/dev/null 2>&1
  local hash=`./test/get-hash.sh $f`
  local sha1=`./test/sha1.sh test/cache/$hash`

  if [[ -z "$expected" ]]; then echo SHA1=$sha1;
  elif [[ "$sha1" != "$expected" ]]; then
    echo "FAILURE: SHA1 mismatch $sha1 != $expected for tiling of $f at test/cache/$hash"
    exit 1
  fi
}

echo "clearing cache..."
./test/clear-cache.sh

test_image NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG 0790b191c33408a4ae268d678b6077c6a1c079c7
test_image NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG 945960bfaf532277b0e48013421e9323da2047d2
test_image N_LRGB_1622XRZS_0790198_CYL_L_AUTOGENJ01.IMG               5d738126f1d796d4aa0d8eb1269a72a72855838f
test_image ZLF_1618_0810574341_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 3f380efa9dc6ab116c9e2a593652bec4d736cbb3
test_image ZLF_1618_0810574647_473FDR_N0790198ZCAM09697_0340LMJ01.IMG d43612e6c512fa742b2330d0e600c3eb4e2c38d9
test_image ZLF_1618_0810575350_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 5f34138636aa0254a76518bd62fef09033e7689a
test_image ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 6b07603ef210678713dc78be90358571082663a5
test_image Z_LRGB_1618XRZS_0790198_ORR_L_45M01CMJ08.IMG               661a2f948fc17737be4954fc9dde13170e427a36

echo "SUCCESS"
