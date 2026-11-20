#!/usr/bin/env bash
# Builds the artifacts under test from this checkout: the TileCLI jar and the
# localhost-fscache war, copied to tests/e2e/.cache/build/.
#
#   bash tests/e2e/scripts/build-product.sh
#
# By default this builds with the `stub` Maven profile, which swaps the internal
# `mars` library for gov.nasa.jpl.ammos.ids:mission-image-stub: an API-compatible
# placeholder with no mission knowledge, built from https://github.com/NASA-AMMOS/Astria
# (stub/). With the stub every product is tiled as a plain image and RDR overlays are
# unavailable; tests that need the real library are tagged @mission-library.
#
# Set TILE_SERVICE_LIBRARY=mars to build against the real library instead. That needs
# a Maven settings.xml that can resolve it; this script never adds a private repository.
set -euo pipefail
# the repository this script is in, wherever it is called from
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."

out=tests/e2e/.cache/build
library=${TILE_SERVICE_LIBRARY:-stub}

case "$library" in
  stub)
    extra=",stub"
    stub_version=$(mvn -B -q -P stub help:evaluate -Dexpression=version.mars -DforceStdout 2>/dev/null \
      || sed -n 's:.*<version.mars>\(.*-stub\.[0-9]*\)</version.mars>.*:\1:p' pom.xml | tail -1)
    stub_dir="${HOME}/.m2/repository/gov/nasa/jpl/ammos/ids/mission-image-stub/${stub_version}"
    if [ ! -f "${stub_dir}/mission-image-stub-${stub_version}.jar" ]; then
      cat >&2 <<MSG
build-product: gov.nasa.jpl.ammos.ids:mission-image-stub:${stub_version} is not in the local Maven repository.
  Install it from source, then rerun this script:
    git clone https://github.com/NASA-AMMOS/Astria astria && mvn -B -f astria/stub/pom.xml install
MSG
      exit 1
    fi
    ;;
  mars) extra="" ;;
  *) echo "build-product: TILE_SERVICE_LIBRARY must be 'stub' or 'mars', got '$library'" >&2; exit 1 ;;
esac

mkdir -p "$out"
# always clean: the shade plugin otherwise folds the previous shaded jar, and with it
# the previously selected library, into the new one
mvn -B -P "jar${extra}" clean package
cp target/image_tiler.jar "$out/image_tiler.jar"
mvn -B -P "war-localhost-fscache${extra}" clean package
cp target/image_tiler.war "$out/image_tiler.war"
rev=$(git rev-parse --short HEAD)
git diff --quiet -- src webapp || rev="$rev+local changes"
echo "$rev" > "$out/REVISION"
echo "$library" > "$out/LIBRARY"
echo "build-product: $out/image_tiler.jar and image_tiler.war ($rev, library: $library)"
