#!/usr/bin/env bash

script_dir=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $script_dir

base="https://d1ejlg980osaur.cloudfront.net/m20/r14/mars2020_mastcamz_ops_calibrated/data/sol/01618/ids/fdr/zcam"
curl -O $base/ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG
curl -O $base/ZLF_1618_0810575350_473FDR_N0790198ZCAM09697_0340LMJ01.IMG
curl -O $base/ZLF_1618_0810574647_473FDR_N0790198ZCAM09697_0340LMJ01.IMG
curl -O $base/ZLF_1618_0810574341_473FDR_N0790198ZCAM09697_0340LMJ01.IMG

base="https://d1ejlg980osaur.cloudfront.net/m20/r14/mars2020_mastcamz_ops_mosaic/data/sol/01618/ids/rdr/mosaic"
curl -O $base/Z_LRGB_1618XRZS_0790198_ORR_L_45M01CMJ08.IMG

base="https://d1ejlg980osaur.cloudfront.net/m20/r14/mars2020_navcam_ops_calibrated/data/sol/01618/ids/fdr/ncam"
curl -O $base/NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG

base="https://d1ejlg980osaur.cloudfront.net/m20/r14/mars2020_navcam_ops_stereo/data/sol/01616/ids/rdr/ncam"
curl -O $base/NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG

base="https://d1ejlg980osaur.cloudfront.net/m20/r15/mars2020_navcam_ops_mosaic/data/sol/01622/ids/rdr/mosaic"
curl -O $base/N_LRGB_1622XRZS_0790198_CYL_L_AUTOGENJ01.IMG
