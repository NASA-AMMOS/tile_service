# Testing

These test scripts and procedures require a `bash` (or `bash` compatible) shell with the following utilities

* `curl`
* `java` and `javac` version 21 or greater
* `mvn` 3.9 or greater
* python 3
* `unzip`
* `find`
* `sort`
* `xargs`
* `shasum`
* `cut`

## Data Preparation

In the `test/` directory run `unzip -o finder-data.zip` to create directories `test/00050/`, `test/00051/`, and `test/00052/`.  These are a mock of three sols of Mars 2020 data for testing the finder functionality.  Nearly all files below these directories are intentionally empty.

Run `test/download-data.sh` to download some Mars 2020 test images.  These are not checked into git due to their size.  They will be downloaded into the `test/` directory without any additional subdirectories.

## Manual Command Line Interface (CLI) Test

Run `test/build-jar.sh` to build `test/image_tiler.jar`.  Then run

```
./test/run-cli.sh test/NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG 

./test/open-viewer.sh NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG 
```

## Automated CLI Test

Run `test/build-jar.sh`, then `test/auto-test-cli.sh`.  It will print SUCCESS or FAILURE.

## Manual Tomcat Localhost Test

Run `test/download-tomcat.sh` to download Apache Tomcat 9.x and unpack it in `test/tomcat/`.

Run `test/build-war.sh` to build `image_tiler.war` using the `webapp/WEB-INF/localhost-fscache-web.xml` configuration and install it to `test/tomcat/webapps/`.  This will allow processing of local image files under `test/` as well as image files from `http://` URLs.  Computed tilings will be saved locally under `test/cache/`.

Optionally run `test/clear-cache.sh` to clear existing cached tilings.

Run `test/run-tomcat.sh` to launch tomcat.

Test that the `image_tiler` servlet is functioning: http://localhost:8080/image_tiler/version

View the test images.  Cached tilings will be used if available, otherwise tilings will be computed on demand:

* http://localhost:8080/image_tiler?image=NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=N_LRGB_1622XRZS_0790198_CYL_L_AUTOGENJ01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=ZLF_1618_0810574341_473FDR_N0790198ZCAM09697_0340LMJ01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=ZLF_1618_0810574647_473FDR_N0790198ZCAM09697_0340LMJ01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=ZLF_1618_0810575350_473FDR_N0790198ZCAM09697_0340LMJ01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG&viewer=true&proxy=http
* http://localhost:8080/image_tiler?image=Z_LRGB_1618XRZS_0790198_ORR_L_45M01CMJ08.IMG&viewer=true&proxy=http

Test custom processing:

* http://localhost:8080/image_tiler?image=NLF_1616_0810395357_230XYZ_N0790198NCAM14615_0A0195J01.IMG&viewer=true&proxy=http&overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D
* http://localhost:8080/image_tiler?image=ZLF_1618_0810575881_473FDR_N0790198ZCAM09697_0340LMJ01.IMG&viewer=true&proxy=http&extremaStretch=true&stretchLow=1000&stretchHigh=3000

Test finder:

* http://localhost:8080/image_tiler/finder/solrange
* http://localhost:8080/image_tiler/finder/edrs?sol=50
* http://localhost:8080/image_tiler/finder/edrs?sol=00051
* http://localhost:8080/image_tiler/finder/edrs?sol=40
* http://localhost:8080/image_tiler/finder/edr?image=00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.IMG
* http://localhost:8080/image_tiler/finder/edr?image=00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.NOT (expect 404)
* http://localhost:8080/image_tiler/finder/rdrs?edr=00051/ids/fdr/ncam/NLF_0051_0671466170_380FDR_N0031950NCAM00408_0A00LLJ01.IMG
* http://localhost:8080/image_tiler/finder/rdrs?edr=00051/ids/fdr/ncam/NLF_0051_0671466170_380FDR_N0031950NCAM00408_0A00LLJ01.NOT (expect 404)
* http://localhost:8080/image_tiler/finder/rdr?image=00051/ids/rdr/ncam/NLF_0051_0671466170_380XYM_N0031950NCAM00408_0A00LLJ01.IMG
* http://localhost:8080/image_tiler/finder/rdr?image=00051/ids/rdr/ncam/NLF_0051_0671466170_380XYM_N0031950NCAM00408_0A00LLJ01.NOT (expect 404)
* http://localhost:8080/image_tiler/finder/label?image=00052/ids/rdr/ncam/NLF_0052_0671564560_659RAS_N0032046NCAM03052_0A0195J02.IMG (this file is not empty)
* http://localhost:8080/image_tiler/finder/label?image=00052/ids/rdr/ncam/NLF_0052_0671564560_659RAS_N0032046NCAM03052_0A0195J02.NOT (expect 404)

## Automated Tomcat Localhost Test

Run `test/download-tomcat.sh`, `test/build-war.sh`, then `test/auto-test-servlet.sh`. It will print SUCCESS or FAILURE.

