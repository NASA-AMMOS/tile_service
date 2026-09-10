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

test_image NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG 79536112803cbb909fd9df97e86a088587fc0b60
test_image NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG e0c7883df604173c1f4477a29db337f7870db554
test_image N_LRGB_1622XRZS_0790198_CYL_L_AUTOGENJ01.IMG               446673bf0a9febe1dec670006b0f01b69cb3251d
test_image ZLF_1618_0810574341_473FDR_N0790198ZCAM09697_0340LMJ01.IMG bec8f71d9c5158e75e5fece94f815db3d88414b1
test_image ZLF_1618_0810574647_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 536b54ac3912de040c508384fa3d47a92707bac1
test_image ZLF_1618_0810575350_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 7c284a02dad5a064f2cd683d9407527e04a48bf4
test_image ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG 6f26800253cbce8acb2ebe217b1ae085423d2c9e
test_image Z_LRGB_1618XRZS_0790198_ORR_L_45M01CMJ08.IMG               957d20261dec6db050b0a716d11db3f69a8dec82

echo "SUCCESS"
